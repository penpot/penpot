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
   [app.main.ui.viewer.handoff.attributes.common :refer [copy-cb color-row]]))

(def fill-attributes [:fill-color :fill-color-gradient])

(defn shape->color [shape]
  {:color (:fill-color shape)
   :opacity (:fill-opacity shape)
   :gradient (:fill-color-gradient shape)
   :id (:fill-ref-id shape)
   :file-id (:fill-ref-file-id shape)})

(defn has-color? [shape]
  (and
   (not (contains? #{:image :text :group} (:type shape)))
   (or (:fill-color shape)
       (:fill-color-gradient shape))))

(mf/defc fill-block [{:keys [shape locale]}]
  (let [color-format (mf/use-state :hex)
        color (shape->color shape)
        handle-copy (copy-cb shape
                             fill-attributes
                             :to-prop "background"
                             :format #(uc/color->background color))]

    [:& color-row {:color color
                   :format @color-format
                   :on-change-format #(reset! color-format %)
                   :on-copy handle-copy}]))

(mf/defc fill-panel
  [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-color?))
        handle-copy (when (= (count shapes) 1)
                      (copy-cb (first shapes)
                               fill-attributes
                               :to-prop "background"
                               :format #(-> shapes first shape->color uc/color->background)))]

    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.fill")]
        (when handle-copy
          [:button.attributes-copy-button
           {:on-click handle-copy}
           i/copy])]

       (for [shape shapes]
         [:& fill-block {:key (str "fill-block-" (:id shape))
                         :shape shape
                         :locale locale}])])))
