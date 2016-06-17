(ns leiningen.jruby
  (:use [clojure.java.io :only (file)])
  (:require [lancet.core :as lancet]
            [leiningen.classpath :as classpath]
            [clojure.string :as str]
            [leiningen.core.main :as core])
  (:import [org.jruby Main]
           [org.apache.tools.ant.types Path]
           [org.apache.tools.ant.types Environment$Variable]
           [org.apache.tools.ant ExitException]))

(def default-options
  {:mode "1.8"
   :bundler-version "1.12.5"
   :sub-directory ""})

(defn- opts [project]
  (merge default-options (:jruby-options project)))

(defn root-dir [project]
  (-> (:root project)
    (file (-> project opts :sub-directory))
    (.getPath)))

(def gem-dir ".lein-gems")

(defn- gem-path-18
  [project]
  (str (root-dir project) "/" gem-dir "/jruby/1.8"))

(defn- gem-path-19
  [project]
  (str (root-dir project) "/" gem-dir "/jruby/1.9"))

(defn- gem-path
  [project]
  (if (= (:mode (opts project)) "1.8")
    (gem-path-18 project)
    (gem-path-19 project)))

(defn- bundler-version
  [project]
  (:bundler-version (opts project)))

(defn- gem-dir-arg
  [project]
  (format "-i%s" (gem-path project)))

(defn- task-props [project]
  {:classname "org.jruby.Main"})

(.addTaskDefinition lancet/ant-project "java" org.apache.tools.ant.taskdefs.Java)

(defn- create-jruby-task
  [project keys]
  (let [full-jruby-dir (file (root-dir project) "src")
        url-classpath (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
        classpath (str/join java.io.File/pathSeparatorChar (map #(.getPath %) url-classpath))
        task (doto (lancet/instantiate-task lancet/ant-project "java"
                                              (task-props project))
                  (.setClasspath (Path. lancet/ant-project classpath)))]

      ; this should really add all source paths
      ;(.setValue (.createArg task) (format "-I%s" (.getAbsolutePath full-jruby-dir)))
      ;(.setValue (.createArg task) "-rubygems")

      (.setValue (.createArg task) (format "--%s" (:mode (opts project))))

      (doseq [k keys] (.setValue (.createArg task) k))

      (.setFailonerror task false)
      (.setFork task (not (= "irb" (second keys))))

      ; i still don't get how it picks up the Gemfile and Rakefile with this set.. ?
      (if (.exists full-jruby-dir) (.setDir task full-jruby-dir))
      task))

(defn- set-gem-path
  [task gem-path]
  (let [envvar (new Environment$Variable)]
    ;(.setNewenvironment task true)
    (.setKey envvar "GEM_PATH")
    (.setValue envvar (str gem-path))
    (.addEnv task envvar)))

(defn- set-gem-home
  [task gem-home]
  (let [envvar (new Environment$Variable)]
    ;(.setNewenvironment task true)
    (.setKey envvar "GEM_HOME")
    (.setValue envvar (str gem-home))
    (.addEnv task envvar)))

(defn- set-path
  [task gem-dir]
  (let [envvar (new Environment$Variable)]
    (.setKey envvar "PATH")
    (.setValue envvar (str gem-dir "/bin"))
    (.addEnv task envvar)))

(defn- jruby-exec
  [project & keys]
  (let [task (create-jruby-task project keys)]
    (core/debug (str "jruby exec" keys))

    ; this may not be a good idea, but can't find another way to get the rubygems bin picked up
    ; another option might be to put it on the classpath. kind of a pain to do that and I'm lazy
    ; right now :P
    (apply set-path [task (gem-path project)])

    (apply set-gem-home [task (gem-path project)])

    (.execute task)))

(defn- jruby-bundle-exec
  [project & keys]
  (let [task (create-jruby-task project keys)
        bundler-path (gem-path project)]

    (core/debug (str "bundle exec" keys))

    ; this may not be a good idea, but can't find another way to get the rubygems bin picked up
    ; another option might be to put it on the classpath. kind of a pain to do that and I'm lazy
    ; right now :P
    (apply set-path [task bundler-path])

    (apply set-gem-home [task bundler-path])
    (apply set-gem-path [task bundler-path])

    (.execute task)))

(defn- any-starts-with?
  [prefix strs]
  (some (fn [str] (.startsWith str prefix)) strs))

(defn- ensure-gem-dir
  [project]
  (.mkdir (file (root-dir project) gem-dir)))

(defn- ensure-gems
  [project & gems]
  (apply ensure-gem-dir [project])
  (apply jruby-exec (concat
    [project "-S" "gem" "install"] gems ["--conservative" (gem-dir-arg project)])))

(defn- ensure-bundler
  [project]
  (ensure-gems project
    "bundler"
    (format "-v%s" (bundler-version project))))

(defn- ensure-gem
  [project gem]
  (apply ensure-gems [project gem]))

(defn- rake
  [project & args]
    (apply ensure-gem [project "rake"])
    (apply jruby-exec (concat [project "-S" "rake"] args)))

(defn- bundle
  [project & args]
  (apply ensure-bundler [project])
  (if (or (empty? args) (= (first args) "install"))
    (apply jruby-bundle-exec (concat [project "-S" "bundle"] args ["--path" (str (root-dir project) "/" gem-dir) "--gemfile" (str (root-dir project) "/Gemfile")]))
    (if (= "exec" (first args))
      (apply jruby-bundle-exec (concat [project "-S"] (rest args)))
      (apply jruby-bundle-exec (concat [project "-S" "bundle"] args)))))

(defn- gem
  [project & args]
  (apply ensure-gem-dir [project])
  (if (any-starts-with? (first args) ["install" "uninstall" "update"])
    (apply jruby-exec (concat
      [project "-S" "gem"] args [(gem-dir-arg project)]))
    (apply jruby-exec (concat
      [project "-S" "gem"] args ))))

(defn jruby
  "Run a JRuby command"
  [project & keys]
  (case (first keys)
    "rake" (apply rake (cons project (rest keys)))
    "bundle" (apply bundle (cons project (rest keys)))
    "irb" (apply jruby-exec (concat [project "-S"] keys))
    ;"exec" (apply jruby-exec (cons project (rest keys)))
    "gem" (apply gem (cons project (rest keys)))
    "-S" (apply jruby-exec (cons project keys))
    "-v" (apply jruby-exec (cons project keys))
    "-e" (apply jruby-exec (cons project keys))))
