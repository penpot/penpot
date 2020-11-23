;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.handoff.attributes.text
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.util.data :as d]
   [app.util.i18n :refer [t]]
   [app.util.color :as uc]
   [app.util.text :as ut]
   [app.main.fonts :as fonts]
   [app.main.ui.icons :as i]
   [app.util.webapi :as wapi]
   [app.main.ui.handoff.attributes.common :refer [color-row]]
   [app.util.code-gen :as cg]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button]]))

(defn has-text? [shape]
  (:content shape))

(def file-typographies-ref
  (l/derived (l/in [:viewer-data :file :typographies]) st/state))

(defn make-typographies-library-ref [file-id]
  (let [get-library
        (fn [state]
          (get-in state [:viewer-libraries file-id :data :typographies]))]
    #(l/derived get-library st/state)))


(def properties [:fill-color
                 :fill-color-gradient
                 :font-family
                 :font-style
                 :font-size
                 :line-height
                 :letter-spacing
                 :text-decoration
                 :text-transform])

(defn shape->color [shape]
  {:color (:fill-color shape)
   :opacity (:fill-opacity shape)
   :gradient (:fill-color-gradient shape)
   :id (:fill-color-ref-id shape)
   :file-id (:fill-color-ref-file shape)})

(def params
  {:to-prop {:fill-color "color"
             :fill-color-gradient "color"}
   :format  {:font-family #(str "'" % "'")
             :font-style #(str "'" % "'")
             :font-size #(str % "px")
             :line-height #(str % "px")
             :letter-spacing #(str % "px")
             :text-decoration name
             :text-transform name
             :fill-color #(-> %2 shape->color uc/color->background)
             :fill-color-gradient #(-> %2 shape->color uc/color->background)}})

(defn copy-style-data
  ([style]
   (cg/generate-css-props style properties params))
  ([style & properties]
   (cg/generate-css-props style properties params)))

(mf/defc typography-block [{:keys [shape locale text style full-style]}]
  (let [typography-library-ref (mf/use-memo
                                (mf/deps (:typography-ref-file style))
                                (make-typographies-library-ref (:typography-ref-file style)))
        typography-library (mf/deref typography-library-ref)

        file-typographies (mf/deref file-typographies-ref)

        color-format (mf/use-state :hex)
        color (shape->color style)

        typography (get (or typography-library file-typographies) (:typography-ref-id style))]

    [:div.attributes-text-block
     (if (:typography-ref-id style)
       [:div.attributes-typography-name-row
        [:div.typography-entry
         [:div.typography-sample
          {:style {:font-family (:font-family typography)
                   :font-weight (:font-weight typography)
                   :font-style (:font-style typography)}}
          (t locale "workspace.assets.typography.sample")]]
        [:div.typography-entry-name (:name typography)]
        [:& copy-button {:data (copy-style-data typography)}]]

       [:div.attributes-typography-row
        [:div.typography-sample
         {:style {:font-family (:font-family full-style)
                  :font-weight (:font-weight full-style)
                  :font-style (:font-style full-style)}}
         (t locale "workspace.assets.typography.sample")]
        [:& copy-button {:data (copy-style-data style)}]])

     [:div.attributes-content-row
      [:pre.attributes-content (str/trim text)]
      [:& copy-button {:data (str/trim text)}]]

     (when (or (:fill-color style) (:fill-color-gradient style))
       [:& color-row {:format @color-format
                      :color (shape->color style)
                      :copy-data (copy-style-data style :fill-color :fill-color-gradient)
                      :on-change-format #(reset! color-format %)}])

     (when (:font-id style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-family")]
        [:div.attributes-value (-> style :font-id fonts/get-font-data :name)]
        [:& copy-button {:data (copy-style-data style :font-family)}]])

     (when (:font-style style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-style")]
        [:div.attributes-value (str (:font-style style))]
        [:& copy-button {:data (copy-style-data style :font-style)}]])

     (when (:font-size style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.font-size")]
        [:div.attributes-value (str (:font-size style)) "px"]
        [:& copy-button {:data (copy-style-data style :font-size)}]])

     (when (:line-height style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.line-height")]
        [:div.attributes-value (str (:line-height style)) "px"]
        [:& copy-button {:data (copy-style-data style :line-height)}]])

     (when (:letter-spacing style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.letter-spacing")]
        [:div.attributes-value (str (:letter-spacing style)) "px"]
        [:& copy-button {:data (copy-style-data style :letter-spacing)}]])

     (when (:text-decoration style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.text-decoration")]
        [:div.attributes-value (->> style :text-decoration (str "handoff.attributes.typography.text-decoration.") (t locale))]
        [:& copy-button {:data (copy-style-data style :text-decoration)}]])

     (when (:text-transform style)
       [:div.attributes-unit-row
        [:div.attributes-label (t locale "handoff.attributes.typography.text-transform")]
        [:div.attributes-value (->> style :text-transform (str "handoff.attributes.typography.text-transform.") (t locale))]
        [:& copy-button {:data (copy-style-data style :text-transform)}]])]))


(mf/defc text-block [{:keys [shape locale]}]
  (let [font (ut/search-text-attrs (:content shape)
                                   (keys ut/default-text-attrs))
        style-text-blocks (->> (keys ut/default-text-attrs)
                               (ut/parse-style-text-blocks (:content shape))
                               (remove (fn [[style text]] (str/empty? (str/trim text))))
                               (mapv (fn [[style text]] (vector (merge ut/default-text-attrs style) text))))

        font (merge ut/default-text-attrs font)]
    (for [[idx [full-style text]] (map-indexed vector style-text-blocks)]
      (let [previus-style (first (nth style-text-blocks (dec idx) nil))
            style (d/remove-equal-values full-style previus-style)

            ;; If the color is set we need to add opacity otherwise the display will not work
            style (cond-> style
                    (:fill-color style)
                    (assoc :fill-opacity (:fill-opacity full-style)))]
        [:& typography-block {:shape shape
                              :locale locale
                              :full-style full-style
                              :style style
                              :text text}]))))

(mf/defc text-panel [{:keys [shapes locale]}]
  (let [shapes (->> shapes (filter has-text?))]
    (when (seq shapes)
      [:div.attributes-block
       [:div.attributes-block-title
        [:div.attributes-block-title-text (t locale "handoff.attributes.typography")]]

       (for [shape shapes]
         [:& text-block {:shape shape
                         :locale locale}])])))

