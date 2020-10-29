;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.shadow
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.i18n :refer [t]]
   [app.util.code-gen :as cg]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.handoff.attributes.common :refer [copy-cb color-row]]))

(defn has-shadow? [shape]
  (:shadow shape))

(mf/defc shadow-block [{:keys [shape locale shadow]}]
  (let [color-format (mf/use-state :hex)]
    [:div.attributes-shadow-block
     [:div.attributes-shadow-row
      [:div.attributes-label (->> shadow :style name (str "handoff.attributes.shadow.style.") (t locale))]
      [:div.attributes-shadow
       [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.offset-x")]
       [:div.attributes-value (str (:offset-x shadow))]]

      [:div.attributes-shadow
       [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.offset-y")]
       [:div.attributes-value (str (:offset-y shadow))]]

      [:div.attributes-shadow
       [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.blur")]
       [:div.attributes-value (str (:blur shadow))]]

      [:div.attributes-shadow
       [:div.attributes-label (t locale "handoff.attributes.shadow.shorthand.spread")]
       [:div.attributes-value (str (:spread shadow))]]

      [:button.attributes-copy-button
       {:on-click (copy-cb shadow
                           :style
                           :to-prop "box-shadow"
                           :format #(cg/shadow->css shadow))}
       i/copy]]
     [:& color-row {:color (:color shadow)
                    :format @color-format
                    :on-change-format #(reset! color-format %)}]]))

(mf/defc shadow-panel [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-shadow?))
        handle-copy-shadow (when (= (count shapes) 1)
                             (copy-cb (first shapes)
                                      :shadow
                                      :to-prop "box-shadow"
                                      :format #(str/join ", " (map cg/shadow->css (:shadow (first shapes))))))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.shadow")]
        (when handle-copy-shadow
          [:button.attributes-copy-button {:on-click handle-copy-shadow} i/copy])]

       [:div.attributes-shadow-blocks
        (for [shape shapes]
          (for [shadow (:shadow shape)]
            [:& shadow-block {:shape shape
                              :locale locale
                              :shadow shadow}]))]])))
