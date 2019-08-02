;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.shapes
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [rumext.core :as mx]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.attrs :as attrs]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.geom.matrix :as gmt]))

;; (def render-component group/render-component)
;; (def shape group/component-container)

(defn render-shape
  [shape]
  (case (:type shape)
    ;; :group (group-component shape)
    :text (text/text-component shape)
    :icon (icon/icon-component shape)
    :rect (rect/rect-component shape)
    :path (path/path-component shape)
    :image (image/image-component shape)
    :circle (circle/circle-component shape)))


(mf/def shape-container
  :mixins [mf/reactive mf/memo]
  :init
  (fn [own {:keys [id] :as props}]
    (assoc own ::shape-ref (-> (l/in [:shapes id])
                               (l/derive st/state))))

  :render
  (fn [own {:keys [id] :as props}]
    (when-let [shape (mf/react (::shape-ref own))]
      (when-not (:hidden shape)
        (render-shape shape)))))

;; NOTE: temporal workaround
(mx/defc shape
  [id]
  (mf/element shape-container {:id id}))


