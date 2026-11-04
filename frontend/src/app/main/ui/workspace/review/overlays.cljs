;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.overlays
  "What the review mode and the tasks draw over the canvas: the changes of
  the branch since the review opened, with a bar to go through them, and
  the layer picker that links a task to another layer."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.time :as ct]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.tasks :as dwtk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.review.common :as rvc]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(defn- screen-rect
  "The box of `shape` on the screen, inside a layer translated by the
  viewbox."
  [shape zoom]
  (let [{:keys [x y width height]} (:selrect shape)]
    #js {:left (dm/str (* x zoom) "px")
         :top (dm/str (* y zoom) "px")
         :width (dm/str (* width zoom) "px")
         :height (dm/str (* height zoom) "px")}))

;; --- Changes

(mf/defc change-box*
  {::mf/private true}
  [{:keys [entry shape zoom is-active]}]
  (let [kind (:kind entry)]
    [:div {:class (stl/css-case :change-box true
                                :is-active is-active
                                :is-added (= kind :added))
           :style (screen-rect shape zoom)}
     [:span {:class (stl/css-case :change-tag true
                                  :is-active is-active
                                  :is-added (= kind :added))}
      (if is-active
        (tr "workspace.review.text-and-detail" (:name shape) (rvc/change-kind-label kind))
        (rvc/change-kind-label kind))]]))

(mf/defc changes-navigator*
  "The bar that goes through the changes shown on the canvas."
  {::mf/private true}
  [{:keys [entry index total]}]
  (let [profiles (mf/deref refs/profiles)

        on-prev  (mf/use-fn (mf/deps index) #(st/emit! (dwrv/show-change (dec index))))
        on-next  (mf/use-fn (mf/deps index) #(st/emit! (dwrv/show-change (inc index))))
        on-back  (mf/use-fn #(st/emit! (dwrv/back-to-details)))
        on-close (mf/use-fn #(st/emit! (dwrv/hide-changes)))]

    [:div {:class (stl/css :navigator)
           :role "region"
           :aria-label (tr "workspace.review.changes.label")}
     [:> icon-button* {:variant "ghost"
                       :icon i/arrow-left
                       :aria-label (tr "workspace.review.changes.previous")
                       :disabled (zero? total)
                       :on-click on-prev}]
     [:> text* {:as "span" :typography t/body-small :class (stl/css :navigator-count)}
      (tr "workspace.review.changes.position" (if (pos? total) (inc index) 0) total)]
     [:> icon-button* {:variant "ghost"
                       :icon i/arrow-right
                       :aria-label (tr "workspace.review.changes.next")
                       :disabled (zero? total)
                       :on-click on-next}]
     (if (some? entry)
       [:> text* {:as "span" :typography t/body-small :class (stl/css :navigator-text)}
        [:strong {:class (stl/css :navigator-name)} (:name entry)]
        " "
        (tr "workspace.review.changes.by"
            (rvc/change-kind-label-lower (:kind entry))
            (rvc/profile-name profiles (:profile-id entry))
            (ct/format-inst (:changed-at entry) :localized-time))]
       [:> text* {:as "span" :typography t/body-small :class (stl/css :navigator-text)}
        (tr "workspace.review.changes.none")])
     [:div {:class (stl/css :navigator-separator)}]
     [:> button* {:variant "ghost" :on-click on-back}
      (tr "workspace.review.changes.back")]
     [:> icon-button* {:variant "ghost"
                       :icon i/close
                       :aria-label (tr "labels.close")
                       :on-click on-close}]]))

(defn- use-change-entries
  "The changes of the activity the change filter keeps."
  []
  (let [activity (mf/deref refs/review-activity)
        local    (mf/deref refs/review-local)
        kind     (get local :change-filter :all)]
    (mf/with-memo [activity kind]
      (cond->> (:entries activity)
        (not= :all kind) (filterv #(= kind (:kind %)))))))

(mf/defc change-boxes*
  {::mf/private true}
  [{:keys [entries zoom page-id index]}]
  (let [objects (mf/deref refs/workspace-page-objects)
        entries (mf/with-memo [entries]
                  (into [] d/xf:add-index entries))]
    [:*
     (for [entry entries]
       (let [shape (get objects (:id entry))]
         (when (and (= page-id (:page-id entry))
                    (some? shape)
                    (not (cfh/root? shape)))
           [:> change-box* {:key (dm/str (:id entry))
                            :entry entry
                            :shape shape
                            :zoom zoom
                            :is-active (= (::d/index entry) index)}])))]))

;; --- Picking the layer of a task

(mf/defc relink-overlay*
  "Pick the layer a task points at from now on, on the canvas or in the
  layers panel. Escape or Cancel leaves the task as it is."
  {::mf/private true}
  [{:keys [task vbox zoom]}]
  (let [objects  (mf/deref refs/workspace-page-objects)
        selected (mf/deref refs/selected-shapes)
        hover*   (mf/use-state nil)
        hover    (deref hover*)
        start    (mf/use-ref selected)

        on-move
        (mf/use-fn
         (mf/deps objects vbox zoom)
         (fn [event]
           (reset! hover* (dwtk/shape-at-position objects (rvc/event->canvas event vbox zoom)))))

        on-click
        (mf/use-fn
         (mf/deps objects vbox zoom)
         (fn [event]
           (dom/stop-propagation event)
           (when-let [shape (dwtk/shape-at-position objects (rvc/event->canvas event vbox zoom))]
             (st/emit! (dwtk/relink-task shape)))))

        on-cancel
        (mf/use-fn #(st/emit! (dwtk/cancel-relink)))]

    ;; a layer picked in the layers panel links the task too
    (mf/with-effect [selected]
      (when (and (not= selected (mf/ref-val start))
                 (= 1 (count selected)))
        (when-let [shape (get objects (first selected))]
          (st/emit! (dwtk/relink-task shape)))))

    (mf/with-effect []
      (let [on-key (fn [event]
                     (when (kbd/esc? event)
                       (st/emit! (dwtk/cancel-relink))))]
        (.addEventListener js/document "keydown" on-key)
        #(.removeEventListener js/document "keydown" on-key)))

    [:*
     [:div {:class (stl/css :picker)
            :on-pointer-move on-move
            :on-click on-click}
      (when (some? hover)
        [:div {:class (stl/css :threads)
               :style #js {:transform (dm/fmt "translate(%px, %px)"
                                              (* (- (:x vbox)) zoom)
                                              (* (- (:y vbox)) zoom))}}
         [:div {:class (stl/css :change-box :is-active)
                :style (screen-rect hover zoom)}
          [:span {:class (stl/css :change-tag :is-active :is-pick)}
           (tr "workspace.tasks.relink.link-to" (:name hover))]]])]
     [:div {:class (stl/css :picker-banner)
            :role "status"}
      [:> icon* {:icon-id i/view-as-list :size "s" :class (stl/css :picker-icon)}]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :picker-label)}
       (tr "workspace.tasks.relink.pick")]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :picker-title)}
       (:title task)]
      [:> button* {:variant "secondary" :on-click on-cancel}
       (tr "labels.cancel")]]]))

(mf/defc review-overlays*
  [{:keys [vbox vport zoom page-id]}]
  (let [local        (mf/deref refs/review-local)
        tasks-local  (mf/deref refs/tasks-local)
        tasks        (mf/deref refs/tasks)
        show-changes (:show-changes local)
        index        (:index show-changes 0)
        entries      (use-change-entries)
        relink       (some->> (:relink tasks-local) (get tasks))]

    (when (or (some? show-changes) (some? relink))
      [:div {:class (stl/css :overlays)
             :style #js {:width (dm/str (:width vport) "px")
                         :height (dm/str (:height vport) "px")}}
       (when (some? show-changes)
         [:*
          [:div {:class (stl/css :threads)
                 :style #js {:transform (dm/fmt "translate(%px, %px)"
                                                (* (- (:x vbox)) zoom)
                                                (* (- (:y vbox)) zoom))}}
           [:> change-boxes* {:entries entries
                              :zoom zoom
                              :page-id page-id
                              :index index}]]
          [:> changes-navigator* {:entry (get entries index)
                                  :index index
                                  :total (count entries)}]])
       (when (some? relink)
         [:> relink-overlay* {:task relink :vbox vbox :zoom zoom}])])))
