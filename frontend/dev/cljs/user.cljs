(ns cljs.user
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.perf :as perf]
   [cuerdas.core :as str]))

(def data {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :k 8 :l 9 :m {:a {:b {:c {:d {:e {:f 1}}}}}}})

(defn bench-get-in
  [& args]
  (let [iterations 1000000
        keys [:m :a :b :c :d :e :f]]

    (perf/benchmark :f #(get-in data keys nil)
                    :max-iterations iterations
                    :name "get-in-initialized")

    (perf/benchmark :f #(get-in data [:m :a :b :c :d :e :f] nil)
                    :max-iterations iterations
                    :name "get-in")

    (perf/benchmark :f #(dm/get-in data [:m :a :b :c :d :e :f] nil)
                    :max-iterations iterations
                    :name "dm/get-in")))

(defn bench-select-keys
  [& args]
  (let [iterations 1000000
        keys [:k :a :m :d :f :g :k]]
    (perf/benchmark :f #(select-keys data keys)
                    :max-iterations iterations
                    :name "select-keys-initialized")

    (perf/benchmark :f #(select-keys data [:k :a :m :d :f :g :k])
                    :max-iterations iterations
                    :name "select-keys")

    (perf/benchmark :f #(dm/select-keys data [:k :a :m :d :f :g :k])
                    :max-iterations iterations
                    :name "dm/select-keys")))

(defn bench-string-concat
  []
  (perf/benchmark :f #(str "foo" 2 "bar" 3 "baz" 4 "kkk" 5)
                  :max-iterations 500000
                  :name "clojure.core/str")

  (perf/benchmark :f #(dm/str "foo" 2 "bar" 3 "baz" 4 "kkk" 5)
                  :max-iterations 500000
                  :name "app.commons.data.macros/str")
  (let [items ["foo" 2 "bar" 3 "baz" 4 "kkk" 5]]
    (perf/benchmark :f #(str/join "" items)
                    :max-iterations 500000
                    :name "cuerdas.core/join")))

(defn main
  [& [name]]
  (case name
    "str" (bench-string-concat)
    "select-keys" (bench-select-keys)
    "get-in" (bench-get-in)
    (println "available: str select-keys get-in")))


