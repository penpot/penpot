;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns user
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as repl]
   [clojure.walk :refer [macroexpand-all]]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as test]
   [clojure.java.io :as io]
   [criterium.core :refer [quick-bench bench with-progress-reporting]]
   [expound.alpha :as expound]
   [promesa.core :as p]
   [sieppari.core :as sp]
   [sieppari.context :as spx]
   [buddy.core.codecs :as codecs]
   [buddy.core.codecs.base64 :as b64]
   [buddy.core.nonce :as nonce]
   [mount.core :as mount]
   [uxbox.main]
   [uxbox.util.blob :as blob])
  (:gen-class))

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

(def stress-data
  {:shapes [{:id #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :name "Canvas-1", :type :canvas, :page #uuid "1b7d4218-e3be-5254-9582-18f767d30501", :x1 200, :y1 200, :x2 1224, :y2 968} {:stroke-color "#000000", :name "Rect-1-copy-4-copy-1", :y1 334, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "c1966bac-b568-4f8f-9917-227cc37e5672", :x1 674, :proportion 1.353448275862069, :y2 450, :x2 831, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-7-copy-1", :y1 400, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "20f11fd1-e9a2-41b1-a539-1298194c6d07", :x1 836, :proportion 1.353448275862069, :y2 516, :x2 993, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-11", :y1 355, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "e1efb49a-03dd-4b87-b568-f35e3a85cf19", :x1 223, :proportion 1.353448275862069, :y2 471, :x2 380, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-10", :y1 404, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "4bf2440d-38d4-461b-af41-001cfb00be49", :x1 928, :proportion 1.353448275862069, :y2 520, :x2 1085, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-9", :y1 525, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "bd1cb612-3960-4428-b030-ea0e59c70b06", :x1 497, :proportion 1.353448275862069, :y2 641, :x2 654, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-8", :y1 393, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "78104154-3d6b-43dc-8444-873ca3285bca", :x1 395, :proportion 1.353448275862069, :y2 509, :x2 552, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-7", :y1 711, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "35540bc3-e1a9-4052-8cb7-a4b07da229dd", :x1 838, :proportion 1.353448275862069, :y2 827, :x2 995, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-6", :y1 502, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "a4d2ef30-1da9-4aba-98ed-15088aba36e0", :x1 893, :proportion 1.353448275862069, :y2 618, :x2 1050, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-5", :y1 479, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "4de4a87a-74f3-47ce-85e6-a0d5da9adb62", :x1 595, :proportion 1.353448275862069, :y2 595, :x2 752, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-4", :y1 645, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "c6c062d2-20fe-4e48-8035-63358db2df4c", :x1 676, :proportion 1.353448275862069, :y2 761, :x2 833, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-3", :y1 609, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "b3089c39-24f9-4574-8df1-d1e4596a6200", :x1 831, :proportion 1.353448275862069, :y2 725, :x2 988, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-2", :y1 404, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "da7c624b-807a-41b1-84a6-7e14ed4f150c", :x1 733, :proportion 1.353448275862069, :y2 520, :x2 890, :height 2} {:stroke-color "#000000", :name "Rect-1-copy-1", :y1 609, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "ec65a0a8-9de1-48d7-b245-71f98961dc7c", :x1 518, :proportion 1.353448275862069, :y2 725, :x2 675, :height 2} {:stroke-color "#000000", :name "Rect-1", :y1 566, :width 2, :type :rect, :page #uuid "2ef8e638-4018-5581-aa18-0887f126966c", :canvas #uuid "352cbd3c-1336-5793-80f7-31027d0acfe7", :proportion-lock false, :id #uuid "c7eafc25-214a-4d16-abbe-19aa0f2eb25b", :x1 306, :proportion 1.353448275862069, :y2 682, :x2 463, :height 2}]})

;; (def a1 (blob/encode stress-data))
;; (def b1 (blob/encode-with-json stress-data))
;; (def b2 (blob/encode-json-snappy stress-data))

;; --- Development Stuff

(defn- make-secret
  []
  (-> (nonce/random-bytes 64)
      (b64/encode true)
      (codecs/bytes->str)))

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

;; (defn- start-minimal
;;   []
;;   (-> (mount/only #{#'uxbox.config/config
;;                     #'uxbox.db/datasource
;;                     #'uxbox.migrations/migrations})
;;       (mount/start)))

(defn- run-test
  ([] (run-test #"^uxbox.tests.*"))
  ([o]
   ;; (repl/refresh)
   (cond
     (instance? java.util.regex.Pattern o)
     (test/run-all-tests o)

     (symbol? o)
     (if-let [sns (namespace o)]
       (do (require (symbol sns))
           (test/test-vars [(resolve o)]))
       (test/test-ns o)))))
