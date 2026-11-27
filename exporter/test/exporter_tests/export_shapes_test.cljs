;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.export-shapes-test
  (:require
   [app.common.uuid :as uuid]
   [app.handlers.export-shapes :as export-shapes]
   [cljs.test :as t :include-macros true]))

(defn- exports
  [n type scale]
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    (mapv (fn [i]
            {:file-id file-id
             :page-id page-id
             :object-id (uuid/next)
             :name (str "shape-" i)
             :suffix ""
             :scale scale
             :type type})
          (range n))))

(t/deftest browser-exports-are-chunked
  (let [parts (export-shapes/prepare-exports (exports 120 :png 1) "token" false)]
    (t/is (= 3 (count parts)))
    (t/is (= [50 50 20] (mapv (comp count :objects) parts)))))

(t/deftest single-export-names-the-download-and-rendered-object
  (let [items    [(assoc (first (exports 1 :png 2)) :name "Icon" :suffix "@2x")]
        job      (export-shapes/prepare "token" {:exports items :name "Icon@2x"})
        rendered (export-shapes/prepare-exports items "token" false)]
    (t/is (= "Icon@2x.png" (get-in job [:resource :filename])))
    (t/is (= "Icon@2x.png" (get-in rendered [0 :objects 0 :filename])))))
