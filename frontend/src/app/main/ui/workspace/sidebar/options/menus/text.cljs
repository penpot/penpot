;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.menus.text
  (:require
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.common.text :as txt]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.fonts :as fonts]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [typography-entry typography-options]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def text-typography-attrs
  [:typography-ref-id
   :typography-ref-file])

(def text-fill-attrs
  [:fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient])

(def text-font-attrs
  [:font-id
   :font-family
   :font-variant-id
   :font-size
   :font-weight
   :font-style])

(def text-align-attrs
  [:text-align])

(def text-direction-attrs
  [:text-direction])

(def text-spacing-attrs
  [:line-height
   :letter-spacing])

(def text-valign-attrs
  [:vertical-align])

(def text-decoration-attrs
  [:text-decoration])

(def text-transform-attrs
  [:text-transform])

(def shape-attrs
  [:grow-type])

(def root-attrs text-valign-attrs)

(def paragraph-attrs
  (d/concat text-align-attrs
            text-direction-attrs))

(def text-attrs
  (d/concat text-typography-attrs
            text-font-attrs
            text-spacing-attrs
            text-decoration-attrs
            text-transform-attrs))

(def attrs (d/concat #{} shape-attrs root-attrs paragraph-attrs text-attrs))

(mf/defc text-align-options
  [{:keys [ids values on-change] :as props}]
  (let [{:keys [text-align]} values
        handle-change
        (fn [event new-align]
          (on-change {:text-align new-align}))]

    ;; --- Align
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-left")
       :class (dom/classnames :current (= "left" text-align))
       :on-click #(handle-change % "left")}
      i/text-align-left]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-center")
       :class (dom/classnames :current (= "center" text-align))
       :on-click #(handle-change % "center")}
      i/text-align-center]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-right")
       :class (dom/classnames :current (= "right" text-align))
       :on-click #(handle-change % "right")}
      i/text-align-right]
     [:span.tooltip.tooltip-bottom
      {:alt (tr  "workspace.options.text-options.align-justify")
       :class (dom/classnames :current (= "justify" text-align))
       :on-click #(handle-change % "justify")}
      i/text-align-justify]]))

(mf/defc text-direction-options
  [{:keys [ids values on-change] :as props}]
  (let [direction     (:text-direction values)
        handle-change (fn [event val]
                        (on-change {:text-direction val}))]
    ;; --- Align
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.direction-ltr")
       :class (dom/classnames :current (= "ltr" direction))
       :on-click #(handle-change % "ltr")}
      i/text-direction-ltr]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.direction-rtl")
       :class (dom/classnames :current (= "rtl" direction))
       :on-click #(handle-change % "rtl")}
      i/text-direction-rtl]]))

(mf/defc vertical-align
  [{:keys [shapes ids values on-change] :as props}]
  (let [{:keys [vertical-align]} values
        vertical-align (or vertical-align "top")
        handle-change
        (fn [event new-align]
          (on-change {:vertical-align new-align}))]

    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-top")
       :class (dom/classnames :current (= "top" vertical-align))
       :on-click #(handle-change % "top")}
      i/align-top]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-middle")
       :class (dom/classnames :current (= "center" vertical-align))
       :on-click #(handle-change % "center")}
      i/align-middle]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.align-bottom")
       :class (dom/classnames :current (= "bottom" vertical-align))
       :on-click #(handle-change % "bottom")}
      i/align-bottom]]))

(mf/defc grow-options
  [{:keys [ids values on-change] :as props}]
  (let [to-single-value (fn [coll] (if (> (count coll) 1) nil (first coll)))
        grow-type (->> values :grow-type)
        handle-change-grow
        (fn [event grow-type]
          (st/emit! (dch/update-shapes ids #(assoc % :grow-type grow-type))))]

    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.grow-fixed")
       :class (dom/classnames :current (= :fixed grow-type))
       :on-click #(handle-change-grow % :fixed)}
      i/auto-fix]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.grow-auto-width")
       :class (dom/classnames :current (= :auto-width grow-type))
       :on-click #(handle-change-grow % :auto-width)}
      i/auto-width]
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.grow-auto-height")
       :class (dom/classnames :current (= :auto-height grow-type))
       :on-click #(handle-change-grow % :auto-height)}
      i/auto-height]]))

(mf/defc text-decoration-options
  [{:keys [ids values on-change] :as props}]
  (let [{:keys [text-decoration]} values

        text-decoration (or text-decoration "none")

        handle-change
        (fn [event type]
          (on-change {:text-decoration type}))]
    [:div.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.none")
       :class (dom/classnames :current (= "none" text-decoration))
       :on-click #(handle-change % "none")}
      i/minus]

     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.underline")
       :class (dom/classnames :current (= "underline" text-decoration))
       :on-click #(handle-change % "underline")}
      i/underline]

     [:span.tooltip.tooltip-bottom
      {:alt (tr "workspace.options.text-options.strikethrough")
       :class (dom/classnames :current (= "line-through" text-decoration))
       :on-click #(handle-change % "line-through")}
      i/strikethrough]]))

(defn generate-typography-name
  [{:keys [font-id font-variant-id] :as typography}]
  (let [{:keys [name]} (fonts/get-font-data font-id)]
    (assoc typography :name (str name " " (str/title font-variant-id)))))

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [ids type values] :as props}]

  (let [file-id      (mf/use-ctx ctx/current-file-id)
        typographies (mf/deref refs/workspace-file-typography)
        shared-libs  (mf/deref refs/workspace-libraries)
        label        (case type
                       :multiple (tr "workspace.options.text-options.title-selection")
                       :group (tr "workspace.options.text-options.title-group")
                       (tr "workspace.options.text-options.title"))

        emit-update!
        (mf/use-callback
         (fn [id attrs]
           (let [attrs (select-keys attrs root-attrs)]
             (when-not (empty? attrs)
               (st/emit! (dwt/update-root-attrs {:id id :attrs attrs}))))

           (let [attrs (select-keys attrs paragraph-attrs)]
             (when-not (empty? attrs)
               (st/emit! (dwt/update-paragraph-attrs {:id id :attrs attrs}))))

           (let [attrs (select-keys attrs text-attrs)]
             (when-not (empty? attrs)
               (st/emit! (dwt/update-text-attrs {:id id :attrs attrs}))))))

        on-change
        (mf/use-callback
         (mf/deps ids)
         (fn [attrs]
           (run! #(emit-update! % attrs) ids)))

        typography
        (mf/use-memo
         (mf/deps values file-id shared-libs)
         (fn []
           (cond
             (and (:typography-ref-id values)
                  (not= (:typography-ref-id values) :multiple)
                  (not= (:typography-ref-file values) file-id))
             (-> shared-libs
                 (get-in [(:typography-ref-file values) :data :typographies (:typography-ref-id values)])
                 (assoc :file-id (:typography-ref-file values)))

             (and (:typography-ref-id values)
                  (not= (:typography-ref-id values) :multiple)
                  (= (:typography-ref-file values) file-id))
             (get typographies (:typography-ref-id values)))))

        on-convert-to-typography
        (fn [event]
          (let [setted-values (-> (d/without-nils values)
                                  (select-keys
                                   (d/concat text-font-attrs
                                             text-spacing-attrs
                                             text-transform-attrs)))
                typography (merge txt/default-typography setted-values)
                typography (generate-typography-name typography)]
            (let [id (uuid/next)]
              (st/emit! (dwl/add-typography (assoc typography :id id) false))
              (run! #(emit-update! % {:typography-ref-id id
                                      :typography-ref-file file-id}) ids))))

        handle-detach-typography
        (mf/use-callback
         (mf/deps on-change)
         (fn []
           (on-change {:typography-ref-file nil
                       :typography-ref-id nil})))

        handle-change-typography
        (mf/use-callback
         (mf/deps typography file-id)
         (fn [changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        opts #js {:ids ids
                  :values values
                  :on-change on-change}]

    [:div.element-set
     [:div.element-set-title
      [:span label]
      (when (not typography)
        [:div.add-page {:on-click on-convert-to-typography} i/close])]

     (cond
       typography
       [:& typography-entry {:typography typography
                             :read-only? (not= (:typography-ref-file values) file-id)
                             :file (get shared-libs (:typography-ref-file values))
                             :on-detach handle-detach-typography
                             :on-change handle-change-typography}]

       (= (:typography-ref-id values) :multiple)
       [:div.multiple-typography
        [:div.multiple-typography-text (tr "workspace.libraries.text.multiple-typography")]
        [:div.multiple-typography-button {:on-click handle-detach-typography
                                          :title (tr "workspace.libraries.text.multiple-typography-tooltip")} i/unchain]]

       :else
       [:> typography-options opts])

     [:div.element-set-content

      [:div.row-flex
       [:> text-align-options opts]
       [:> vertical-align opts]]

      [:div.row-flex
       [:> text-decoration-options opts]
       [:> text-direction-options opts]]

      [:div.row-flex
       [:> grow-options opts]
       [:div.align-icons]]]]))


