(ns build
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.tools.build.api :as b]
   [clojure.java.io]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/penpot.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/copy-dir
   {:src-dirs ["src" "resources"]
    :target-dir class-dir})

  (b/uber
   {:class-dir class-dir
    :uber-file jar-file
    :main 'clojure.main
    :exclude [#".*Log4j2Plugins\.dat$"]
    :basis basis}))

(defn compile [_]
  (b/javac
   {:src-dirs ["dev/java"]
    :class-dir class-dir
    :basis basis
    :javac-opts ["-source" "17" "-target" "17"]}))
