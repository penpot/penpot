;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.shapes.path
  (:require
   [app.common.logging :as log]
   [app.common.types.path :as path]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [rumext.v2 :as mf]))

(defn- path-data->string
  [path-data]
  (cond
    (nil? path-data)
    ""

    (path/path-data? path-data)
    (.toString path-data)

    :else
    (let [path-data (path/path-data path-data)]
      (.toString path-data))))

(mf/defc path-shape
  {::mf/props :obj}
  [{:keys [shape]}]
  (let [path-data (get shape :path-data)
        pdata     (mf/with-memo [path-data]
                    (try
                      (path-data->string path-data)
                      (catch :default cause
                        (log/error :hint "unexpected error on formatting path"
                                   :shape-name (:name shape)
                                   :shape-id (:id shape)
                                   :cause cause)
                        "")))]
    [:& shape-custom-strokes {:shape shape}
     [:path {:d pdata}]]))
