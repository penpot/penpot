;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.formats :as fmt]
   [app.main.ui.viewer.inspect.attributes.common :refer [color-row]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn has-text? [shape]
  (:content shape))

(def file-typographies-ref
  (l/derived (l/in [:viewer :file :data :typographies]) st/state))

(defn make-typographies-library-ref [file-id]
  (let [get-library
        (fn [state]
          (get-in state [:viewer-libraries file-id :data :typographies]))]
    #(l/derived get-library st/state)))

(defn fill->color [{:keys [fill-color fill-opacity fill-color-gradient fill-color-ref-id fill-color-ref-file fill-image]}]
  {:color fill-color
   :opacity fill-opacity
   :gradient fill-color-gradient
   :id fill-color-ref-id
   :file-id fill-color-ref-file
   :image fill-image})

(defn copy-style-data
  [style & properties]
  (->> properties
       (map #(dm/str (d/name %) ": " (get style %) ";"))
       (str/join "\n")))

(mf/defc typography-block
  [{:keys [text style]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        typography-library-ref
        (mf/use-memo
         (mf/deps (:typography-ref-file style))
         (make-typographies-library-ref (:typography-ref-file style)))

        typography-library (mf/deref typography-library-ref)
        file-typographies-viewer    (mf/deref file-typographies-ref)
        file-typographies-workspace (mf/deref refs/workspace-file-typography)

        file-library-workspace      (get (mf/deref refs/workspace-libraries) (:typography-ref-file style))
        typography-external-lib (get-in file-library-workspace [:data :typographies (:typography-ref-id style)])

        color-format       (mf/use-state :hex)

        typography (or (get (or typography-library file-typographies-viewer file-typographies-workspace) (:typography-ref-id style)) typography-external-lib)]

    (if new-css-system
      [:div {:class (stl/css :attributes-content)}
       (when (:fills style)
         (for [[idx fill] (map-indexed vector (:fills style))]
           [:& color-row {:key idx
                          :format @color-format
                          :color (fill->color fill)
                          :copy-data (copy-style-data fill :fill-color :fill-color-gradient)
                          :on-change-format #(reset! color-format %)}]))

       (when (:typography-ref-id style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data typography :font-family :font-weight :font-style)}
            [:div {:class (stl/css :button-children)} (:name typography)]]]])

       (when (:font-id style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)} (tr "inspect.attributes.typography.font-family")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :font-family)}
            [:div {:class (stl/css :button-children)}
             (-> style :font-id fonts/get-font-data :name)]]]])

       (when (:font-style style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.font-style")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :font-style)}
            [:div {:class (stl/css :button-children)}
             (str (:font-style style))]]]])

       (when (:font-size style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.font-size")]
          [:div  {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :font-size)}
            [:div {:class (stl/css :button-children)}
             (fmt/format-pixels (:font-size style))]]]])

       (when (:font-weight style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.font-weight")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :font-weight)}
            [:div {:class (stl/css :button-children)}
             (str (:font-weight style))]]]])

       (when (:line-height style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.line-height")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :line-height)}
            [:div {:class (stl/css :button-children)}
             (fmt/format-number (:line-height style))]]]])

       (when (:letter-spacing style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.letter-spacing")]
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :letter-spacing)}
            [:div {:class (stl/css :button-children)}
             (fmt/format-pixels (:letter-spacing style))]]]])

       (when (:text-decoration style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.text-decoration")]
          ;; Execution time translation strings:
          ;;   inspect.attributes.typography.text-decoration.none
          ;;   inspect.attributes.typography.text-decoration.strikethrough
          ;;   inspect.attributes.typography.text-decoration.underline
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :text-decoration)}
            [:div {:class (stl/css :button-children)}
             (tr (dm/str "inspect.attributes.typography.text-decoration." (:text-decoration style)))]]]])

       (when (:text-transform style)
         [:div {:class (stl/css :text-row)}
          [:div {:class (stl/css :global/attr-label)}
           (tr "inspect.attributes.typography.text-transform")]
          ;; Execution time translation strings:
          ;;   inspect.attributes.typography.text-transform.lowercase
          ;;   inspect.attributes.typography.text-transform.none
          ;;   inspect.attributes.typography.text-transform.titlecase
          ;;   inspect.attributes.typography.text-transform.uppercase
          ;;   inspect.attributes.typography.text-transform.unset
          [:div {:class (stl/css :global/attr-value)}
           [:& copy-button {:data (copy-style-data style :text-transform)}
            [:div {:class (stl/css :button-children)}
             (tr (dm/str "inspect.attributes.typography.text-transform." (:text-transform style)))]]]])

       [:& copy-button {:data (str/trim text)
                        :class (stl/css :attributes-content-row)}
        [:span {:class (stl/css :content)
                :style {:font-family (:font-family style)
                        :font-weight (:font-weight style)
                        :font-style (:font-style style)}}
         (str/trim text)]]]


      [:div.attributes-text-block
       (if (:typography-ref-id style)
         [:div.attributes-typography-name-row
          [:div.typography-entry
           [:div.typography-sample
            {:style {:font-family (:font-family typography)
                     :font-weight (:font-weight typography)
                     :font-style (:font-style typography)}}
            (tr "workspace.assets.typography.text-styles")]]
          [:div.typography-entry-name (:name typography)]
          [:& copy-button {:data (copy-style-data style :font-family :font-weight :font-style)}]]

         [:div.attributes-typography-row
          [:div.typography-sample
           {:style {:font-family (:font-family style)
                    :font-weight (:font-weight style)
                    :font-style (:font-style style)}}
           (tr "workspace.assets.typography.text-styles")]
          [:& copy-button {:data (copy-style-data style :font-family :font-weight :font-style)}]])

       (when (:fills style)
         (for [[idx fill] (map-indexed vector (:fills style))]
           [:& color-row {:key idx
                          :format @color-format
                          :color (fill->color fill)
                          :copy-data (copy-style-data fill :fill-color :fill-color-gradient)
                          :on-change-format #(reset! color-format %)}]))

       (when (:font-id style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.font-family")]
          [:div.attributes-value (-> style :font-id fonts/get-font-data :name)]
          [:& copy-button {:data (copy-style-data style :font-family)}]])

       (when (:font-style style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.font-style")]
          [:div.attributes-value (str (:font-style style))]
          [:& copy-button {:data (copy-style-data style :font-style)}]])

       (when (:font-size style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.font-size")]
          [:div.attributes-value (fmt/format-pixels (:font-size style))]
          [:& copy-button {:data (copy-style-data style :font-size)}]])

       (when (:font-weight style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.font-weight")]
          [:div.attributes-value (str (:font-weight style))]
          [:& copy-button {:data (copy-style-data style :font-weight)}]])

       (when (:line-height style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.line-height")]
          [:div.attributes-value (fmt/format-number (:line-height style))]
          [:& copy-button {:data (copy-style-data style :line-height)}]])

       (when (:letter-spacing style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.letter-spacing")]
          [:div.attributes-value (fmt/format-pixels (:letter-spacing style))]
          [:& copy-button {:data (copy-style-data style :letter-spacing)}]])

       (when (:text-decoration style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.text-decoration")]
          ;; Execution time translation strings:
          ;;   inspect.attributes.typography.text-decoration.none
          ;;   inspect.attributes.typography.text-decoration.strikethrough
          ;;   inspect.attributes.typography.text-decoration.underline
          [:div.attributes-value (tr (dm/str "inspect.attributes.typography.text-decoration." (:text-decoration style)))]
          [:& copy-button {:data (copy-style-data style :text-decoration)}]])

       (when (:text-transform style)
         [:div.attributes-unit-row
          [:div.attributes-label (tr "inspect.attributes.typography.text-transform")]
          ;; Execution time translation strings:
          ;;   inspect.attributes.typography.text-transform.lowercase
          ;;   inspect.attributes.typography.text-transform.none
          ;;   inspect.attributes.typography.text-transform.titlecase
          ;;   inspect.attributes.typography.text-transform.uppercase
          [:div.attributes-value (tr (dm/str "inspect.attributes.typography.text-transform." (:text-transform style)))]
          [:& copy-button {:data (copy-style-data style :text-transform)}]])

       [:div.attributes-content-row
        [:pre.attributes-content (str/trim text)]
        [:& copy-button {:data (str/trim text)}]]])))


(mf/defc text-block [{:keys [shape]}]
  (let [style-text-blocks (->> (:content shape)
                               (txt/content->text+styles)
                               (remove (fn [[_ text]] (str/empty? (str/trim text))))
                               (mapv (fn [[style text]] (vector (merge txt/default-text-attrs style) text))))]

    (for [[idx [full-style text]] (map-indexed vector style-text-blocks)]
      [:& typography-block {:key idx
                            :shape shape
                            :style full-style
                            :text text}])))

(mf/defc text-panel
  [{:keys [shapes]}]
  (when-let [shapes (seq (filter has-text? shapes))]
    (let [new-css-system (mf/use-ctx ctx/new-css-system)]
      (if new-css-system
        [:div {:class (stl/css :attributes-block)}
         [:& title-bar {:collapsable? false
                        :title        (tr "inspect.attributes.typography")
                        :class        (stl/css :title-spacing-text)}]

         (for [shape shapes]
           [:& text-block {:shape shape
                           :key (str "text-block" (:id shape))}])]

        [:div.attributes-block
         [:div.attributes-block-title
          [:div.attributes-block-title-text (tr "inspect.attributes.typography")]]

         (for [shape shapes]
           [:& text-block {:shape shape
                           :key (str "text-block" (:id shape))}])]))))
