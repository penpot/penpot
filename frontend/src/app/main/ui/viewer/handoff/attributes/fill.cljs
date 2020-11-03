;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.fill
  (:require
   [rumext.alpha :as mf]
   [app.util.i18n :refer [t]]
   [app.util.color :as uc]
   [app.main.ui.icons :as i]
   [app.util.code-gen :as cg]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.viewer.handoff.attributes.common :refer [color-row]]))

(def fill-attributes [:fill-color :fill-color-gradient])

(defn shape->color [shape]
  {:color (:fill-color shape)
   :opacity (:fill-opacity shape)
   :gradient (:fill-color-gradient shape)
   :id (:fill-color-ref-id shape)
   :file-id (:fill-color-ref-file shape)})

(defn has-color? [shape]
  (and
   (not (contains? #{:image :text :group} (:type shape)))
   (or (:fill-color shape)
       (:fill-color-gradient shape))))

(defn copy-data [shape]
  (cg/generate-css-props
   shape
   fill-attributes
   {:to-prop "background"
    :format #(uc/color->background (shape->color shape))}))

(mf/defc fill-block [{:keys [shape locale]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color shape)]

    [:& color-row {:color color
                   :format @color-format
                   :on-change-format #(reset! color-format %)
                   :copy-data (copy-data shape)}]))

(mf/defc fill-panel
  [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-color?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.fill")]
        (when (= (count shapes) 1)
          [:& copy-button {:data (copy-data (first shapes))}])]

       (for [shape shapes]
         [:& fill-block {:key (str "fill-block-" (:id shape))
                         :shape shape
                         :locale locale}])])))
