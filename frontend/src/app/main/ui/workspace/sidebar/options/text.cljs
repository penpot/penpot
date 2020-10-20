;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.text
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.main.ui.icons :as i]
   [app.common.data :as d]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.ui.workspace.sidebar.options.measures :refer [measure-attrs measures-menu]]
   [app.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [app.main.ui.workspace.sidebar.options.shadow :refer [shadow-menu]]
   [app.main.ui.workspace.sidebar.options.typography :refer [typography-entry typography-options]]
   [app.main.ui.workspace.sidebar.options.blur :refer [blur-menu]]
   [app.util.dom :as dom]
   [app.main.fonts :as fonts]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.text :as ut]
   ["slate" :refer [Transforms]]))

(def text-typography-attrs [:typography-ref-id :typography-ref-file])
(def text-fill-attrs [:fill-color :fill-opacity :fill-color-ref-id :fill-color-ref-file :fill-color-gradient :fill :opacity ])
(def text-font-attrs [:font-id :font-family :font-variant-id :font-size :font-weight :font-style])
(def text-align-attrs [:text-align])
(def text-spacing-attrs [:line-height :letter-spacing])
(def text-valign-attrs [:vertical-align])
(def text-decoration-attrs [:text-decoration])
(def text-transform-attrs [:text-transform])

(def root-attrs (d/concat text-valign-attrs
                          text-align-attrs))
(def paragraph-attrs text-align-attrs)
(def text-attrs (d/concat text-typography-attrs
                          text-font-attrs
                          text-align-attrs
                          text-spacing-attrs
                          text-decoration-attrs
                          text-transform-attrs))

(mf/defc text-align-options
  [{:keys [editor ids values locale on-change] :as props}]
  (let [{:keys [text-align]} values

        text-align (or text-align "left")

        handle-change
        (fn [event new-align]
          (on-change {:text-align new-align}))]

    ;; --- Align
    [:div.row-flex.align-icons
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-left")
       :class (dom/classnames :current (= "left" text-align))
       :on-click #(handle-change % "left")}
      i/text-align-left]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-center")
       :class (dom/classnames :current (= "center" text-align))
       :on-click #(handle-change % "center")}
      i/text-align-center]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-right")
       :class (dom/classnames :current (= "right" text-align))
       :on-click #(handle-change % "right")}
      i/text-align-right]
     [:span.tooltip.tooltip-bottom
      {:alt (t locale "workspace.options.text-options.align-justify")
       :class (dom/classnames :current (= "justify" text-align))
       :on-click #(handle-change % "justify")}
      i/text-align-justify]]))


(mf/defc additional-options
  [{:keys [shapes editor ids values locale on-change] :as props}]
  (let [{:keys [vertical-align]} values

        to-single-value (fn [coll] (if (> (count coll) 1) nil (first coll)))

        grow-type (->> shapes (map :grow-type) (remove nil?) (into #{}) to-single-value)

        vertical-align (or vertical-align "top")

        handle-change-grow
        (fn [event grow-type]
          (st/emit! (dwc/update-shapes ids #(assoc % :grow-type grow-type))))
        
        handle-change
        (fn [event new-align]
          (on-change {:vertical-align new-align}))]

    [:div.row-flex
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-top")
        :class (dom/classnames :current (= "top" vertical-align))
        :on-click #(handle-change % "top")}
       i/align-top]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-middle")
        :class (dom/classnames :current (= "center" vertical-align))
        :on-click #(handle-change % "center")}
       i/align-middle]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.align-bottom")
        :class (dom/classnames :current (= "bottom" vertical-align))
        :on-click #(handle-change % "bottom")}
       i/align-bottom]]

     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.grow-fixed")
        :class (dom/classnames :current (= :fixed grow-type))
        :on-click #(handle-change-grow % :fixed)}
       i/auto-fix]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.grow-auto-width")
        :class (dom/classnames :current (= :auto-width grow-type))
        :on-click #(handle-change-grow % :auto-width)}
       i/auto-width]
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.grow-auto-height")
        :class (dom/classnames :current (= :auto-height grow-type))
        :on-click #(handle-change-grow % :auto-height)}
       i/auto-height]]]))

(mf/defc text-decoration-options
  [{:keys [editor ids values locale on-change] :as props}]
  (let [{:keys [text-decoration]} values

        text-decoration (or text-decoration "none")

        handle-change
        (fn [event type]
          (on-change {:text-decoration type}))]
    [:div.row-flex
     [:span.element-set-subtitle (t locale "workspace.options.text-options.decoration")]
     [:div.align-icons
      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.none")
        :class (dom/classnames :current (= "none" text-decoration))
        :on-click #(handle-change % "none")}
       i/minus]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.underline")
        :class (dom/classnames :current (= "underline" text-decoration))
        :on-click #(handle-change % "underline")}
       i/underline]

      [:span.tooltip.tooltip-bottom
       {:alt (t locale "workspace.options.text-options.strikethrough")
        :class (dom/classnames :current (= "line-through" text-decoration))
        :on-click #(handle-change % "line-through")}
       i/strikethrough]]]))

(defn generate-typography-name [{:keys [font-id font-variant-id] :as typography}]
  (let [{:keys [name]} (fonts/get-font-data font-id)]
    (-> typography
        (assoc :name (str name " " (str/title font-variant-id))))) )

(mf/defc text-menu
  {::mf/wrap [mf/memo]}
  [{:keys [ids
           type
           editor
           values
           shapes] :as props}]

  (let [locale (mf/deref i18n/locale)
        typographies (mf/deref refs/workspace-file-typography)
        shared-libs (mf/deref refs/workspace-libraries)
        label (case type
                :multiple (t locale "workspace.options.text-options.title-selection")
                :group (t locale "workspace.options.text-options.title-group")
                (t locale "workspace.options.text-options.title"))

        emit-update!
        (fn [id attrs]
          (let [attrs (select-keys attrs root-attrs)]
            (when-not (empty? attrs)
              (st/emit! (dwt/update-root-attrs {:id id :editor editor :attrs attrs}))))

          (let [attrs (select-keys attrs paragraph-attrs)]
            (when-not (empty? attrs)
              (st/emit! (dwt/update-paragraph-attrs {:id id :editor editor :attrs attrs}))))

          (let [attrs (select-keys attrs text-attrs)]
            (when-not (empty? attrs)
              (st/emit! (dwt/update-text-attrs {:id id :editor editor :attrs attrs})))))

        typography (cond
                     (and (:typography-ref-id values)
                          (not= (:typography-ref-id values) :multiple)
                          (:typography-ref-file values))
                     (-> shared-libs
                         (get-in [(:typography-ref-file values) :data :typographies (:typography-ref-id values)])
                         (assoc :file-id (:typography-ref-file values)))

                     (and (:typography-ref-id values)
                          (not= (:typography-ref-id values) :multiple))
                     (get typographies (:typography-ref-id values)))


        on-convert-to-typography
        (mf/use-callback
         (mf/deps values)
         (fn [event]
           (let [setted-values (-> (d/without-nils values)
                                   (select-keys
                                    (d/concat text-font-attrs
                                              text-spacing-attrs
                                              text-transform-attrs)))
                 typography (merge ut/default-typography setted-values)
                 typography (generate-typography-name typography)]
             (let [id (uuid/next)]
               (st/emit! (dwl/add-typography (assoc typography :id id) false))
               (run! #(emit-update! % {:typography-ref-id id}) ids)))))

        handle-deattach-typography
        (fn []
          (run! #(emit-update! % {:typography-ref-file nil
                                  :typography-ref-id nil})
                ids))

        handle-change-typography
        (fn [changes]
          (st/emit! (dwl/update-typography (merge typography changes))))

        opts #js {:editor editor
                  :ids ids
                  :values values
                  :shapes shapes
                  :on-change (fn [attrs]
                               (run! #(emit-update! % attrs) ids))
                  :locale locale}]

    [:div.element-set
     [:div.element-set-title
      [:span label]
      (when (not typography)
        [:div.add-page {:on-click on-convert-to-typography} i/close])]

     (cond
       typography
       [:& typography-entry {:typography typography
                             :read-only? (some? (:typography-ref-file values))
                             :file (get shared-libs (:typography-ref-file values))
                             :on-deattach handle-deattach-typography
                             :on-change handle-change-typography}]

       (= (:typography-ref-id values) :multiple)
       [:div.multiple-typography
        [:div.multiple-typography-text (t locale "workspace.libraries.text.multiple-typography")]
        [:div.multiple-typography-button {:on-click handle-deattach-typography
                                          :title (t locale "workspace.libraries.text.multiple-typography-tooltip")} i/unchain]]

       :else
       [:> typography-options opts])

     [:div.element-set-content
      [:> text-align-options opts]
      [:> additional-options opts]
      [:> text-decoration-options opts]]]))

(mf/defc options
  [{:keys [shape] :as props}]
  (let [ids [(:id shape)]
        type (:type shape)

        local (deref refs/workspace-local)
        editor (get-in local [:editors (:id shape)])

        measure-values (select-keys shape measure-attrs)

        fill-values (dwt/current-text-values
                     {:editor editor
                      :shape shape
                      :attrs text-fill-attrs})

        fill-values (d/update-in-when fill-values [:fill-color-gradient :type] keyword)

        fill-values (cond-> fill-values
                      ;; Keep for backwards compatibility
                      (:fill fill-values) (assoc :fill-color (:fill fill-values))
                      (:opacity fill-values) (assoc :fill-opacity (:fill fill-values)))

        text-values (merge
                     (dwt/current-root-values
                      {:editor editor :shape shape
                       :attrs root-attrs})
                     (dwt/current-text-values
                      {:editor editor :shape shape
                       :attrs paragraph-attrs})
                     (dwt/current-text-values
                      {:editor editor :shape shape
                       :attrs text-attrs}))]

    [:*
     [:& measures-menu {:ids ids
                        :type type
                        :values measure-values}]
     [:& fill-menu {:ids ids
                    :type type
                    :values fill-values
                    :editor editor}]
     [:& shadow-menu {:ids ids
                      :values (select-keys shape [:shadow])}]
     [:& blur-menu {:ids ids
                    :values (select-keys shape [:blur])}]
     [:& text-menu {:ids ids
                    :type type
                    :values text-values
                    :editor editor
                    :shapes [shape]}]]))
