;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns user
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.java.io :as io]
   [clojure.repl :refer :all]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
   [clj-kondo.core :as kondo]
   [promesa.core :as p]
   [promesa.exec :as px]
   [uxbox.migrations]
   [uxbox.util.storage :as st]
   [uxbox.util.time :as tm]
   [mount.core :as mount]))

;; --- Benchmarking Tools

(defmacro run-quick-bench
  [& exprs]
  `(with-progress-reporting (quick-bench (do ~@exprs) :verbose)))

(defmacro run-quick-bench'
  [& exprs]
  `(quick-bench (do ~@exprs)))

(defmacro run-bench
  [& exprs]
  `(with-progress-reporting (bench (do ~@exprs) :verbose)))

(defmacro run-bench'
  [& exprs]
  `(bench (do ~@exprs)))

;; --- Development Stuff

(defn- start
  []
  (-> #_(mount/except #{#'uxbox.scheduled-jobs/scheduler})
      (mount/start)))

(defn- stop
  []
  (mount/stop))

(defn restart
  []
  (stop)
  (repl/refresh :after 'user/start))

(defn- run-tests
  ([] (run-tests #"^uxbox.tests.*"))
  ([o]
   (repl/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))

(defn lint
  ([] (lint ""))
  ([path]
   (-> (kondo/run!
        {:lint [(str "src/" path)]
         :cache false
         :config {:linters
                  {:unresolved-symbol
                   {:exclude ['(uxbox.services.mutations/defmutation)
                              '(uxbox.services.queries/defquery)
                              '(promesa.core/let)]}}}})
       (kondo/print!))))

(comment
  {:version 1
   :options {}
   :shapes [:id1  :id2]
   :canvas [:id3]
   :shapes-by-id {:id1 {:canvas :id3} :id2 {} :id3 {}}})


(comment
  {:version 2
   :options {}

   :objects
   {:root
    {:type :frame
     :shapes [:sid0 :frame-0]}

    :frame0
    {:type :frame
     :parent :root
     :shapes [:sid1 :sid2]}

    :sid0
    {:type :rect
     :parent :root}

    :sid1
    {:type :rect
     :parent :frame0}

    :sid2
    {:type :group
     :shapes [:sid3 :sid4]
     :parent :frame0}

    :sid3
    {:type :elipse
     :parent :sid2}

    :sid4
    {:type :elipse
     :parent :sid2}}})

(comment
  {:version 3
   :options {}

   :rmap
   {:id1 :root-frame
    :id2 :root-frame
    :id3 :frame-id-1
    :id4 :frame-id-2
    :id5 :frame-id-2
    :id6 :frame-id-2}

   :frames
   {:root-frame
    {:type :frame
     :shapes [:id1 :id2]
     :objects
     {:id1 {:type :rect}
      :id2 {:type :elipse}}}

    :frame-id-1
    {:type :frame
     :shapes [:id3]
     :objects
     {:id3 {:type :path}}}

    :frame-id-2
    {:type :frame
     :shapes [:id4]
     :objects
     {:id4 {:type :group
            :shapes [:id5 :id6]}
      :id5 {:type :path :parent :id4}
      :id6 {:type :elipse :parent :id4}}}}})
