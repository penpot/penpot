;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.inspect.attributes.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.text :as txt]
   [app.common.types.fills :as types.fills]
   [app.common.types.text :as types.text]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button*]]
   [app.main.ui.components.title-bar :refer [inspect-title-bar*]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.inspect.attributes.common :refer [color-row]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- has-text? [shape]
  (:content shape))

(def ^:private file-typographies-ref
  (l/derived (l/in [:viewer :file :data :typographies]) st/state))

(defn- make-typographies-library-ref [file-id]
  (let [get-library
        (fn [state]
          (get-in state [:viewer-libraries file-id :data :typographies]))]
    #(l/derived get-library st/state)))

(defn- copy-style-data
  [style & properties]
  (->> properties
       (map #(dm/str (d/name %) ": " (get style %) ";"))
       (str/join "\n")))

(mf/defc typography-block
  [{:keys [text style]}]
  (let [typography-library-ref
        (mf/use-memo
         (mf/deps (:typography-ref-file style))
         (make-typographies-library-ref (:typography-ref-file style)))

        typography-library (mf/deref typography-library-ref)

        ;; FIXME: too many duplicate operations
        file-typographies-viewer    (mf/deref file-typographies-ref)
        file-typographies-workspace (mf/deref refs/workspace-file-typography)

        file-library-workspace      (get (mf/deref refs/files) (:typography-ref-file style))
        typography-external-lib (get-in file-library-workspace [:data :typographies (:typography-ref-id style)])

        color-format*       (mf/use-state :hex)
        color-format        (deref color-format*)

        typography (or (get (or typography-library file-typographies-viewer file-typographies-workspace) (:typography-ref-id style)) typography-external-lib)]

    [:div {:class (stl/css :attributes-content)}
     (when (:fills style)
       (for [[idx fill] (map-indexed vector (:fills style))]
         [:& color-row {:key idx
                        :format color-format
                        :color (types.fills/fill->color fill)
                        :copy-data (copy-style-data fill :fill-color :fill-color-gradient)
                        :on-change-format #(reset! color-format* %)}]))

     (when (:typography-ref-id style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data typography :font-family :font-weight :font-style)
                           :class (stl/css :copy-btn-wrapper)}
          [:div {:class (stl/css :button-children)} (:name typography)]]]])

     (when (:font-id style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)} (tr "inspect.attributes.typography.font-family")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :font-family)}
          [:div {:class (stl/css :button-children)}
           (-> style :font-id fonts/get-font-data :name)]]]])

     (when (:font-style style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.font-style")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :font-style)}
          [:div {:class (stl/css :button-children)}
           (dm/str (:font-style style))]]]])

     (when (:font-size style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.font-size")]
        [:div  {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data (assoc style :font-size (fmt/format-pixels (:font-size style))) :font-size)}
          [:div {:class (stl/css :button-children)}
           (fmt/format-pixels (:font-size style))]]]])

     (when (:font-weight style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.font-weight")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :font-weight)}
          [:div {:class (stl/css :button-children)}
           (dm/str (:font-weight style))]]]])

     (when (:line-height style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.line-height")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :line-height)}
          [:div {:class (stl/css :button-children)}
           (fmt/format-number (:line-height style))]]]])

     (when (:letter-spacing style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.letter-spacing")]
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :letter-spacing)}
          [:div {:class (stl/css :button-children)}
           (fmt/format-pixels (:letter-spacing style))]]]])

     (when (:text-decoration style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.text-decoration")]
              ;; Execution time translation strings:
              ;;   (tr "inspect.attributes.typography.text-decoration.none")
              ;;   (tr "inspect.attributes.typography.text-decoration.strikethrough")
              ;;   (tr "inspect.attributes.typography.text-decoration.underline")
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :text-decoration)}
          [:div {:class (stl/css :button-children)}
           (tr (dm/str "inspect.attributes.typography.text-decoration." (:text-decoration style)))]]]])

     (when (:text-transform style)
       [:div {:class (stl/css :text-row)}
        [:div {:class (stl/css :global/attr-label)}
         (tr "inspect.attributes.typography.text-transform")]
              ;; Execution time translation strings:
              ;;   (tr "inspect.attributes.typography.text-transform.lowercase")
              ;;   (tr "inspect.attributes.typography.text-transform.none")
              ;;   (tr "inspect.attributes.typography.text-transform.capitalize")
              ;;   (tr "inspect.attributes.typography.text-transform.uppercase")
              ;;   (tr "inspect.attributes.typography.text-transform.unset")
        [:div {:class (stl/css :global/attr-value)}
         [:> copy-button* {:data (copy-style-data style :text-transform)}
          [:div {:class (stl/css :button-children)}
           (tr (dm/str "inspect.attributes.typography.text-transform." (:text-transform style)))]]]])

     [:> copy-button* {:data (str/trim text)
                       :class (stl/css :attributes-content-row)}
      [:span {:class (stl/css :content)
              :style {:font-family (:font-family style)
                      :font-weight (:font-weight style)
                      :font-style (:font-style style)}}
       (str/trim text)]]]))


(mf/defc text-block [{:keys [shape]}]
  (let [style-text-blocks (->> (:content shape)
                               (txt/content->text+styles)
                               (remove (fn [[_ text]] (str/empty? (str/trim text))))
                               (mapv (fn [[style text]] (vector (merge (types.text/get-default-text-attrs) style) text))))]

    (for [[idx [full-style text]] (map-indexed vector style-text-blocks)]
      [:& typography-block {:key idx
                            :shape shape
                            :style full-style
                            :text text}])))

(mf/defc text-panel
  [{:keys [shapes]}]
  (when-let [shapes (seq (filter has-text? shapes))]
    [:div {:class (stl/css :attributes-block)}
     [:> inspect-title-bar*
      {:title (tr "inspect.attributes.typography")
       :class (stl/css :title-spacing-text)}]

     (for [shape shapes]
       [:& text-block {:shape shape
                       :key (dm/str "text-block" (:id shape))}])]))
