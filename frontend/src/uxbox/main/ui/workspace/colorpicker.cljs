;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.colorpicker
  (:require [lentes.core :as l]
            [uxbox.util.router :as rt]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.lightbox :as lbx]
            [uxbox.main.ui.colorpicker :as cp]
            [uxbox.main.ui.workspace.recent-colors :refer [recent-colors]]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.geom :as geom]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.data :refer [parse-int parse-float read-string]]))

(defn- focus-shape
  [id]
  (-> (l/in [:shapes id])
      (l/derive st/state)))

(mx/defcs shape-colorpicker
  {:mixins [mx/reactive mx/static]}
  [own {:keys [x y shape attr] :as opts}]
  (let [shape (mx/react (focus-shape shape))
        left (- x 260)
        top (- y 50)]
    (letfn [(change-color [color]
              (let [attrs {:color color}]
                (st/emit!
                 (case attr
                   :stroke (uds/update-stroke-attrs (:id shape) attrs)
                   :fill (uds/update-fill-attrs (:id shape) attrs)))))]
      [:div.colorpicker-tooltip
       {:style {:left (str left "px")
                :top (str top "px")}}

       (cp/colorpicker
        :theme :small
        :value (get shape attr "#000000")
        :on-change change-color)
       (recent-colors shape change-color)])))

(mx/defcs page-colorpicker
  {:mixins [mx/reactive mx/static]}
  [own {:keys [x y attr default] :as opts}]
  (let [{:keys [id metadata] :as page} (mx/react wb/page-ref)]
    (letfn [(change-color [color]
              (let [metadata (assoc metadata attr color)]
                (st/emit! (udp/update-metadata id metadata))))]
      [:div.colorpicker-tooltip
       {:style {:left (str (- x 260) "px")
                :top (str (- y 50) "px")}}

       (cp/colorpicker
        :theme :small
        :value (get metadata attr default)
        :on-change change-color)])))

(defmethod lbx/render-lightbox :workspace/shape-colorpicker
  [params]
  (shape-colorpicker params))

(defmethod lbx/render-lightbox :workspace/page-colorpicker
  [params]
  (page-colorpicker params))
