;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]))

(defn render-shape
  [shape]
  (mf/html
   (case (:type shape)
     :text [:& text/text-component {:shape shape}]
     :icon [:& icon/icon-component {:shape shape}]
     :rect [:& rect/rect-component {:shape shape}]
     :path [:& path/path-component {:shape shape}]
     :image [:& image/image-component {:shape shape}]
     :circle [:& circle/circle-component {:shape shape}])))

(mf/defc shape-component
  {:wrap [mf/wrap-memo]}
  [{:keys [id] :as props}]
  (let [shape-iref (mf/use-memo {:deps #js [id]
                                 :init #(-> (l/in [:shapes id])
                                            (l/derive st/state))})]
    (when-let [shape (mf/deref shape-iref)]
      (when-not (:hidden shape)
        (render-shape shape)))))
