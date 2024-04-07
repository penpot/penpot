;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.export
  "Assets exportation common components."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.exports :as de]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.shapes :refer [shape-wrapper]]
   [app.main.worker :as uw]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr c]]
   [app.util.strings :as ust]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private neutral-icon
  (i/icon-xref :msg-neutral (stl/css :icon)))

(def ^:private error-icon
  (i/icon-xref :delete-text (stl/css :icon)))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc export-multiple-dialog
  [{:keys [exports title cmd no-selection]}]
  (let [lstate          (mf/deref refs/export)
        in-progress?    (:in-progress lstate)

        exports         (mf/use-state exports)

        all-exports     (deref exports)
        all-checked?    (every? :enabled all-exports)
        all-unchecked?  (every? (complement :enabled) all-exports)

        enabled-exports (into []
                              (comp (filter :enabled)
                                    (map #(dissoc % :shape :enabled)))
                              all-exports)

        cancel-fn
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide)))

        accept-fn
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide)
                    (de/request-multiple-export
                     {:exports enabled-exports
                      :cmd cmd})))

        on-toggle-enabled
        (mf/use-fn
         (mf/deps exports)
         (fn [event]
           (let [index (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (d/parse-integer))]
             (when (some? index)
               (swap! exports update-in [index :enabled] not)))))

        change-all
        (fn [_]
          (swap! exports (fn [exports]
                           (mapv #(assoc % :enabled (not all-checked?)) exports))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css-case :modal-container true
                                 :empty (empty? all-exports))}

      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} title]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click cancel-fn}
        i/close]]

      [:*
       [:div {:class (stl/css :modal-content)}
        (if (> (count all-exports) 0)
          [:*
           [:div {:class (stl/css :selection-header)}
            [:button {:class (stl/css :selection-btn)
                      :on-click change-all}
             [:span {:class (stl/css :checkbox-wrapper)}
              (cond
                all-checked? [:span {:class (stl/css-case :checkobox-tick true
                                                          :global/checked true)}
                              i/tick]
                all-unchecked? [:span {:class (stl/css-case :checkobox-tick true
                                                            :global/uncheked true)}]
                :else [:span {:class (stl/css-case :checkobox-tick true
                                                   :global/intermediate true)}
                       i/remove-icon])]]
            [:div {:class (stl/css :selection-title)}
             (tr "dashboard.export-multiple.selected"
                 (c (count enabled-exports))
                 (c (count all-exports)))]]
           [:div {:class (stl/css :selection-wrapper)}
            [:div {:class (stl/css-case :selection-list true
                                        :selection-shadow (> (count all-exports) 8))}
             (for [[index {:keys [shape suffix] :as export}] (d/enumerate @exports)]
               (let [{:keys [x y width height]} (:selrect shape)]
                 [:div {:class (stl/css :selection-row)
                        :key (:id shape)}
                  [:button {:class (stl/css :selection-btn)
                            :data-value (str index)
                            :on-click on-toggle-enabled}
                   [:span {:class (stl/css :checkbox-wrapper)}
                    (if (:enabled export)
                      [:span {:class (stl/css-case :checkobox-tick true
                                                   :global/checked true)}
                       i/tick]
                      [:span {:class (stl/css-case :checkobox-tick true
                                                   :global/uncheked true)}])]

                   [:div {:class (stl/css :image-wrapper)}
                    (if (some? (:thumbnail shape))
                      [:img {:src (:thumbnail shape)}]
                      [:svg {:view-box (dm/str x " " y " " width " " height)
                             :width 24
                             :height 20
                             :version "1.1"
                             :xmlns "http://www.w3.org/2000/svg"
                             :xmlnsXlink "http://www.w3.org/1999/xlink"
                                                       ;; Fix Chromium bug about color of html texts
                                                       ;; https://bugs.chromium.org/p/chromium/issues/detail?id=1244560#c5
                             :style {:-webkit-print-color-adjust :exact}
                             :fill "none"}

                       [:& shape-wrapper {:shape shape}]])]

                   [:div {:class (stl/css :selection-name)}
                    (cond-> (:name shape) suffix (str suffix))]
                   (when (:scale export)
                     [:div {:class (stl/css :selection-scale)}
                      (dm/str (ust/format-precision (* width (:scale export)) 2) "x"
                              (ust/format-precision (* height (:scale export)) 2))])

                   (when (:type export)
                     [:div {:class (stl/css :selection-extension)}
                      (-> export :type d/name str/upper)])]]))]]]

          [:& no-selection])]

       (when (> (count all-exports) 0)
         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :cancel-button)
                    :type "button"
                    :value (tr "labels.cancel")
                    :on-click cancel-fn}]

           [:input {:class (stl/css-case :accept-btn true
                                         :btn-disabled (or in-progress? all-unchecked?))
                    :disabled (or in-progress? all-unchecked?)
                    :type "button"
                    :value (if in-progress?
                             (tr "workspace.options.exporting-object")
                             (tr "labels.export"))
                    :on-click (when-not in-progress? accept-fn)}]]])]]]))

(mf/defc shapes-no-selection []
  [:div {:class (stl/css :no-selection)}
   [:p {:class (stl/css :modal-msg)}
    (tr "dashboard.export-shapes.no-elements")]
   [:p {:class (stl/css :modal-scd-msg)} (tr "dashboard.export-shapes.how-to")]
   [:a {:target "_blank"
        :class (stl/css :modal-link)
        :href "https://help.penpot.app/user-guide/exporting/ "}
    (tr "dashboard.export-shapes.how-to-link")]])

(mf/defc export-shapes-dialog
  {::mf/register modal/components
   ::mf/register-as :export-shapes}
  [{:keys [exports]}]
  (let [title (tr "dashboard.export-shapes.title")]
    [:& export-multiple-dialog
     {:exports exports
      :title title
      :cmd :export-shapes
      :no-selection shapes-no-selection}]))

(mf/defc export-frames
  {::mf/register modal/components
   ::mf/register-as :export-frames}
  [{:keys [exports]}]
  (let [title (tr "dashboard.export-frames.title")]
    [:& export-multiple-dialog
     {:exports exports
      :title title
      :cmd :export-frames}]))

(mf/defc export-progress-widget
  {::mf/wrap [mf/memo]}
  []
  (let [state             (mf/deref refs/export)
        profile           (mf/deref refs/profile)
        theme             (or (:theme profile) "default")
        is-default-theme? (= "default" theme)
        error?            (:error state)
        healthy?          (:healthy? state)
        detail-visible?   (:detail-visible state)
        widget-visible?   (:widget-visible state)
        progress          (:progress state)
        exports           (:exports state)
        total             (count exports)
        complete?         (= progress total)
        circ              (* 2 Math/PI 12)
        pct               (- circ (* circ (/ progress total)))

        pwidth (if error?
                 280
                 (/ (* progress 280) total))
        color  (cond
                 error?         clr/new-danger
                 healthy?       (if is-default-theme?
                                  clr/new-primary
                                  clr/new-primary-light)
                 (not healthy?) clr/new-warning)

        background-clr (if is-default-theme?
                         clr/background-quaternary
                         clr/background-quaternary-light)
        title  (cond
                 error?          (tr "workspace.options.exporting-object-error")
                 complete?       (tr "workspace.options.exporting-complete")
                 healthy?        (tr "workspace.options.exporting-object")
                 (not healthy?)  (tr "workspace.options.exporting-object-slow"))

        retry-last-export
        (mf/use-fn #(st/emit! (de/retry-last-export)))

        toggle-detail-visibility
        (mf/use-fn #(st/emit! (de/toggle-detail-visibililty)))]

    [:*
     (when widget-visible?
       [:div {:class (stl/css :export-progress-widget)
              :on-click toggle-detail-visibility}
        [:svg {:width "24" :height "24"}
         [:circle {:r "10"
                   :cx "12"
                   :cy "12"
                   :fill "transparent"
                   :stroke background-clr
                   :stroke-width "4"}]
         [:circle {:r "10"
                   :cx "12"
                   :cy "12"
                   :fill "transparent"
                   :stroke color
                   :stroke-width "4"
                   :stroke-dasharray (dm/str circ " " circ)
                   :stroke-dashoffset pct
                   :transform "rotate(-90 12,12)"
                   :style {:transition "stroke-dashoffset 1s ease-in-out"}}]]])

     (when detail-visible?
       [:div {:class (stl/css-case :export-progress-modal true
                                   :has-error error?)}
        (if error?
          error-icon
          neutral-icon)

        [:p {:class (stl/css :export-progress-title)}
         title
         (if error?
           [:button {:class (stl/css :retry-btn)
                     :on-click retry-last-export}
            (tr "workspace.options.retry")]

           [:p {:class (stl/css :progress)}
            (dm/str progress " / " total)])]

        [:button {:class (stl/css :progress-close-button)
                  :on-click toggle-detail-visibility}
         close-icon]

        (when-not error?
          [:svg {:class (stl/css :progress-bar)
                 :height 4
                 :width 280}
           [:g
            [:path {:d "M0 0 L280 0"
                    :stroke background-clr
                    :stroke-width 30}]
            [:path {:d (dm/str "M0 0 L280 0")
                    :stroke color
                    :stroke-width 30
                    :fill "transparent"
                    :stroke-dasharray 280
                    :stroke-dashoffset (- 280 pwidth)
                    :style {:transition "stroke-dashoffset 1s ease-in-out"}}]]])])]))

(def ^:const options [:all :merge :detach])

(mf/defc export-entry
  {::mf/wrap-props false}
  [{:keys [file]}]
  [:div {:class (stl/css-case :file-entry true
                              :loading  (:loading? file)
                              :success  (:export-success? file)
                              :error    (:export-error? file))}

   [:div {:class (stl/css :file-name)}
    [:span {:class (stl/css :file-icon)}
     (cond (:export-success? file) i/tick
           (:export-error? file)   i/close
           (:loading? file)        i/loader-pencil)]

    [:div {:class (stl/css :file-name-label)}
     (:name file)]]])

(defn- mark-file-error
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-error? true
                  :loading? false))
        files))

(defn- mark-file-success
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-success? true
                  :loading? false))
        files))

(def export-types
  [:all :merge :detach])

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as :export
   ::mf/wrap-props false}
  [{:keys [team-id files has-libraries? binary? features]}]
  (let [state*          (mf/use-state
                         #(let [files (mapv (fn [file] (assoc file :loading? true)) files)]
                            {:status :prepare
                             :selected :all
                             :files files}))

        state           (deref state*)
        selected        (:selected state)
        status          (:status state)



        start-export
        (mf/use-fn
         (mf/deps team-id selected files features)
         (fn []
           (swap! state* assoc :status :exporting)
           (->> (uw/ask-many!
                 {:cmd (if binary? :export-binary-file :export-standard-file)
                  :team-id team-id
                  :features features
                  :export-type selected
                  :files files})
                (rx/mapcat #(->> (rx/of %)
                                 (rx/delay 1000)))
                (rx/subs!
                 (fn [msg]
                   (cond
                     (= :error (:type msg))
                     (swap! state* update :files mark-file-error (:file-id msg))

                     (= :finish (:type msg))
                     (do
                       (swap! state* update :files mark-file-success (:file-id msg))
                       (dom/trigger-download-uri (:filename msg) (:mtype msg) (:uri msg)))))))))

        on-cancel
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))))

        on-accept
        (mf/use-fn
         (mf/deps start-export)
         (fn [event]
           (dom/prevent-default event)
           (start-export)))

        on-change
        (mf/use-fn
         (fn [event]
           (let [type (-> (dom/get-target event)
                          (dom/get-data "type")
                          (keyword))]
             (swap! state* assoc :selected type))))]

    (mf/with-effect [has-libraries?]
      ;; Start download automatically when no libraries
      (when-not has-libraries?
        (start-export)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)}
        (tr "dashboard.export.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click on-cancel} i/close]]

      (cond
        (= status :prepare)
        [:*
         [:div {:class (stl/css :modal-content)}
          [:p {:class (stl/css :modal-msg)} (tr "dashboard.export.explain")]
          [:p {:class (stl/css :modal-scd-msg)} (tr "dashboard.export.detail")]

          (for [type export-types]
            [:div {:class (stl/css :export-option true)
                   :key (name type)}
             [:label {:for (str "export-" type)
                      :class (stl/css-case :global/checked (= selected type))}
                                ;; Execution time translation strings:
                                ;;   dashboard.export.options.all.message
                                ;;   dashboard.export.options.all.title
                                ;;   dashboard.export.options.detach.message
                                ;;   dashboard.export.options.detach.title
                                ;;   dashboard.export.options.merge.message
                                ;;   dashboard.export.options.merge.title
              [:span {:class (stl/css-case :global/checked (= selected type))}
               (when (= selected type)
                 i/status-tick)]
              [:div {:class (stl/css :option-content)}
               [:h3 {:class (stl/css :modal-subtitle)} (tr (dm/str "dashboard.export.options." (d/name type) ".title"))]
               [:p  {:class (stl/css :modal-msg)} (tr (dm/str "dashboard.export.options." (d/name type) ".message"))]]

              [:input {:type "radio"
                       :class (stl/css :option-input)
                       :id (str "export-" type)
                       :checked (= selected type)
                       :name "export-option"
                       :data-type (name type)
                       :on-change on-change}]]])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :cancel-button)
                    :type "button"
                    :value (tr "labels.cancel")
                    :on-click on-cancel}]

           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "labels.continue")
                    :on-click on-accept}]]]]

        (= status :exporting)
        [:*
         [:div {:class (stl/css :modal-content)}
          (for [file (:files state)]
            [:& export-entry {:file file :key (dm/str (:id file))}])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:input {:class (stl/css :accept-btn)
                    :type "button"
                    :value (tr "labels.close")
                    :disabled (->> state :files (some :loading?))
                    :on-click on-cancel}]]]])]]))
