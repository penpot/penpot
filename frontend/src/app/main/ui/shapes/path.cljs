;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.shapes.path
  (:require
   [app.common.logging :as log]
   [app.main.ui.shapes.attrs :as attrs]
   [app.main.ui.shapes.custom-stroke :refer [shape-custom-strokes]]
   [app.util.object :as obj]
   [app.util.path.format :as upf]
   [rumext.alpha :as mf]))

(mf/defc path-shape
  {::mf/wrap-props false}
  [props]
  (let [shape   (unchecked-get props "shape")
        content (:content shape)
        pdata   (mf/with-memo [content]
                  (try
                    (upf/format-path content)
                    (catch :default e
                      (log/error :hint "unexpected error on formating path"
                                 :shape-name (:name shape)
                                 :shape-id (:id shape)
                                 :cause e)
                       "")))

        props   (-> (attrs/extract-style-attrs shape)
                    (obj/set! "d" pdata))]

    [:& shape-custom-strokes {:shape shape}
     [:> :path props]]))
