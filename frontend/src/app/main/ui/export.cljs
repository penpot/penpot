;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.export
  "Assets exportation common components."
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
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr c]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(mf/defc export-shapes-dialog
  {::mf/register modal/components
   ::mf/register-as :export-shapes}
  [{:keys [exports filename]}]
  (let [lstate          (mf/deref refs/export)
        in-progress?    (:in-progress lstate)

        exports         (mf/use-state exports)

        all-exports     (deref exports)
        all-checked?    (every? :enabled all-exports)
        all-unchecked?  (every? (complement :enabled) all-exports)

        enabled-exports (into [] (filter :enabled) all-exports)

        cancel-fn
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide)))

        accept-fn
        (fn [event]
          (dom/prevent-default event)
          (st/emit! (modal/hide)
                    (de/request-multiple-export {:filename filename :exports enabled-exports})))
        on-toggle-enabled
        (fn [index]
          (swap! exports update-in [index :enabled] not))

        change-all
        (fn [_]
          (swap! exports (fn [exports]
                           (mapv #(assoc % :enabled (not all-checked?)) exports))))
        ]
    [:div.modal-overlay
     [:div.modal-container.export-shapes-dialog
      {:class (when (empty? all-exports) "no-shapes")}

      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "dashboard.export-shapes.title")]]

       [:div.modal-close-button
        {:on-click cancel-fn} i/close]]

      [:*
       [:div.modal-content
        (if (> (count all-exports) 0)
          [:*
           [:div.header
            [:div.field.check {:on-click change-all}
             (cond
               all-checked? [:span i/checkbox-checked]
               all-unchecked? [:span i/checkbox-unchecked]
               :else [:span i/checkbox-intermediate])]
            [:div.field.title (tr "dashboard.export-shapes.selected"
                                  (c (count enabled-exports))
                                  (c (count all-exports)))]]

           [:div.body
            (for [[index {:keys [shape suffix] :as export}] (d/enumerate @exports)]
              (let [{:keys [x y width height]} (:selrect shape)]
                [:div.row
                 [:div.field.check {:on-click #(on-toggle-enabled index)}
                  (if (:enabled export)
                    [:span.checked i/checkbox-checked]
                    [:span.unchecked i/checkbox-unchecked])]

                 [:div.field.image
                  [:svg {:view-box (dm/str x " " y " " width " " height)
                         :width 24
                         :height 20
                         :version "1.1"
                         :xmlns "http://www.w3.org/2000/svg"
                         :xmlnsXlink "http://www.w3.org/1999/xlink"
                         ;; Fix Chromium bug about color of html texts
                         ;; https://bugs.chromium.org/p/chromium/issues/detail?id=1244560#c5
                         :style {:-webkit-print-color-adjust :exact}}

                   [:& shape-wrapper {:shape shape}]]]

                 [:div.field.name (cond-> (:name shape) suffix (str suffix))]
                 [:div.field.scale (dm/str (* width (:scale export)) "x"
                                           (* height (:scale export)) "px ")]
                 [:div.field.extension (-> export :type d/name str/upper)]]))]

           [:div.modal-footer
            [:div.action-buttons
             [:input.cancel-button
              {:type "button"
               :value (tr "labels.cancel")
               :on-click cancel-fn}]

             [:input.accept-button.primary
              {:class (dom/classnames
                       :btn-disabled (or in-progress? all-unchecked?))
               :disabled (or in-progress? all-unchecked?)
               :type "button"
               :value (if in-progress?
                        (tr "workspace.options.exporting-object")
                        (tr "labels.export"))
               :on-click (when-not in-progress? accept-fn)}]]]]

          [:div.no-selection
           [:img {:src "images/export-no-shapes.png" :border "0"}]
           [:p (tr "dashboard.export-shapes.no-elements")]
           [:p (tr "dashboard.export-shapes.how-to")]
           [:p [:a {:target "_blank"
                    :href "https://help.penpot.app/user-guide/exporting/ "}
                (tr "dashboard.export-shapes.how-to-link")]]])]]]]))

(mf/defc export-progress-widget
  {::mf/wrap [mf/memo]}
  []
  (let [state           (mf/deref refs/export)
        error?          (:error state)
        healthy?        (:healthy? state)
        detail-visible? (:detail-visible state)
        widget-visible? (:widget-visible state)
        progress        (:progress state)
        exports         (:exports state)
        total           (count exports)
        circ            (* 2 Math/PI 12)
        pct             (- circ (* circ (/ progress total)))

        pwidth (if error?
                 280
                 (/ (* progress 280) total))
        color  (cond
                 error?         clr/danger
                 healthy?       clr/primary
                 (not healthy?) clr/warning)
        title  (cond
                 error?          (tr "workspace.options.exporting-object-error")
                 healthy?        (tr "workspace.options.exporting-object")
                 (not healthy?)  (tr "workspace.options.exporting-object-slow"))

        retry-last-export
        (mf/use-fn #(st/emit! (de/retry-last-export)))

        toggle-detail-visibility
        (mf/use-fn #(st/emit! (de/toggle-detail-visibililty)))]

    [:*
     (when widget-visible?
       [:div.export-progress-widget {:on-click toggle-detail-visibility}
        [:svg {:width "32" :height "32"}
         [:circle {:r "12"
                   :cx "16"
                   :cy "16"
                   :fill "transparent"
                   :stroke clr/gray-40
                   :stroke-width "4"}]
         [:circle {:r "12"
                   :cx "16"
                   :cy "16"
                   :fill "transparent"
                   :stroke color
                   :stroke-width "4"
                   :stroke-dasharray (dm/str circ " " circ)
                   :stroke-dashoffset pct
                   :transform "rotate(-90 16,16)"
                   :style {:transition "stroke-dashoffset 1s ease-in-out"}}]]])

     (when detail-visible?
       [:div.export-progress-modal-overlay
        [:div.export-progress-modal-container
         [:div.export-progress-modal-header
          [:p.export-progress-modal-title title]
          (if error?
            [:button.btn-secondary.retry {:on-click retry-last-export} (tr "workspace.options.retry")]
            [:p.progress (dm/str progress " / " total)])

          [:button.modal-close-button {:on-click toggle-detail-visibility} i/close]]

         [:svg.progress-bar {:height 8 :width 280}
          [:g
           [:path {:d "M0 0 L280 0"
                   :stroke clr/gray-10
                   :stroke-width 30}]
           [:path {:d (dm/str "M0 0 L280 0")
                   :stroke color
                   :stroke-width 30
                   :fill "transparent"
                   :stroke-dasharray 280
                   :stroke-dashoffset (- 280 pwidth)
                   :style {:transition "stroke-dashoffset 1s ease-in-out"}}]]]]])]))

