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

(defn- content->string
  [content]
  (cond
    (nil? content)
    ""

    (path/content? content)
    (.toString content)

    :else
    (let [content (path/content content)]
      (.toString content))))

(mf/defc path-shape
  {::mf/props :obj}
  [{:keys [shape]}]
  (let [content (get shape :content)
        pdata   (mf/with-memo [content]
                  (try
                    (content->string content)
                    (catch :default cause
                      (log/error :hint "unexpected error on formatting path"
                                 :shape-name (:name shape)
                                 :shape-id (:id shape)
                                 :cause cause)
                      "")))]
    [:& shape-custom-strokes {:shape shape}
     [:path {:d pdata}]]))
