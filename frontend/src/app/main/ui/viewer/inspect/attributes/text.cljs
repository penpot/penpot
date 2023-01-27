;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.attributes.text
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.text :as txt]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.main.ui.viewer.inspect.attributes.common :refer [color-row]]
   [app.util.code-gen :as cg]
   [app.util.color :as uc]
   [app.util.i18n :refer [tr]]
   [app.util.strings :as ust]
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

(defn format-number [number]
  (-> number
      d/parse-double
      (ust/format-precision 2)))

(def properties [:fill-color
                 :fill-color-gradient
                 :font-family
                 :font-style
                 :font-size
                 :font-weight
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
   :format  {:font-family #(dm/str "'" % "'")
             :font-style #(dm/str % )
             :font-size #(dm/str (format-number %) "px")
             :font-weight d/name
             :line-height #(format-number %)
             :letter-spacing #(dm/str (format-number %) "px")
             :text-decoration d/name
             :text-transform d/name
             :fill-color #(-> %2 shape->color uc/color->background)
             :fill-color-gradient #(-> %2 shape->color uc/color->background)}})

(defn copy-style-data
  ([style]
   (cg/generate-css-props style properties params))
  ([style & properties]
   (cg/generate-css-props style properties params)))

(mf/defc typography-block [{:keys [text style]}]
  (let [typography-library-ref (mf/use-memo
                                (mf/deps (:typography-ref-file style))
                                (make-typographies-library-ref (:typography-ref-file style)))
        typography-library (mf/deref typography-library-ref)

        file-typographies (mf/deref file-typographies-ref)

        color-format (mf/use-state :hex)

        typography (get (or typography-library file-typographies) (:typography-ref-id style))]

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
        [:& copy-button {:data (copy-style-data typography)}]]

       [:div.attributes-typography-row
        [:div.typography-sample
         {:style {:font-family (:font-family style)
                  :font-weight (:font-weight style)
                  :font-style (:font-style style)}}
         (tr "workspace.assets.typography.text-styles")]
        [:& copy-button {:data (copy-style-data style)}]])

     (when (:fills style)
       (for [[idx fill] (map-indexed vector (:fills style))]
         [:& color-row {:key idx
                        :format @color-format
                        :color (shape->color fill)
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
        [:div.attributes-value (str (format-number (:font-size style))) "px"]
        [:& copy-button {:data (copy-style-data style :font-size)}]])

     (when (:font-weight style)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "inspect.attributes.typography.font-weight")]
        [:div.attributes-value (str (:font-weight style))]
        [:& copy-button {:data (copy-style-data style :font-weight)}]])

     (when (:line-height style)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "inspect.attributes.typography.line-height")]
        [:div.attributes-value (format-number (:line-height style))]
        [:& copy-button {:data (copy-style-data style :line-height)}]])

     (when (:letter-spacing style)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "inspect.attributes.typography.letter-spacing")]
        [:div.attributes-value (str (format-number (:letter-spacing style))) "px"]
        [:& copy-button {:data (copy-style-data style :letter-spacing)}]])

     (when (:text-decoration style)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "inspect.attributes.typography.text-decoration")]
        ;; Execution time translation strings:
        ;;   inspect.attributes.typography.text-decoration.none
        ;;   inspect.attributes.typography.text-decoration.strikethrough
        ;;   inspect.attributes.typography.text-decoration.underline
        [:div.attributes-value (->> style :text-decoration (str "inspect.attributes.typography.text-decoration.") (tr))]
        [:& copy-button {:data (copy-style-data style :text-decoration)}]])

     (when (:text-transform style)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "inspect.attributes.typography.text-transform")]
        ;; Execution time translation strings:
        ;;   inspect.attributes.typography.text-transform.lowercase
        ;;   inspect.attributes.typography.text-transform.none
        ;;   inspect.attributes.typography.text-transform.titlecase
        ;;   inspect.attributes.typography.text-transform.uppercase
        [:div.attributes-value (->> style :text-transform (str "inspect.attributes.typography.text-transform.") (tr))]
        [:& copy-button {:data (copy-style-data style :text-transform)}]])

     [:div.attributes-content-row
      [:pre.attributes-content (str/trim text)]
      [:& copy-button {:data (str/trim text)}]]]))


(mf/defc text-block [{:keys [shape]}]
  (let [style-text-blocks (->> (keys txt/default-text-attrs)
                               (cg/parse-style-text-blocks (:content shape))
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
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (tr "inspect.attributes.typography")]]

     (for [shape shapes]
       [:& text-block {:shape shape
                       :key (str "text-block" (:id shape))}])]))
