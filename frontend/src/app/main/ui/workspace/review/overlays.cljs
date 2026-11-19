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

(defn screen-rect
  "The box `rect` (canvas coordinates) on the screen, inside a layer
  translated by the viewbox."
  [{:keys [x y width height]} zoom]
  #js {:left (dm/str (* x zoom) "px")
       :top (dm/str (* y zoom) "px")
       :width (dm/str (* width zoom) "px")
       :height (dm/str (* height zoom) "px")})

(defn viewbox-offset
  "The style that moves a layer covering the viewport to the origin of
  the canvas, so what it holds is placed in canvas coordinates."
  [vbox zoom]
  #js {:transform (dm/fmt "translate(%px, %px)"
                          (* (- (:x vbox)) zoom)
                          (* (- (:y vbox)) zoom))})

;; --- Changes

(mf/defc change-box*
  "The mark of a change on the canvas: a dashed box around `rect` with a
  tag that says what happened, in the `tone` of its status. The active
  one is drawn solid. With `on-click` the tag is a button; the box itself
  never takes the pointer, so the canvas under it stays usable."
  [{:keys [class rect zoom label tone is-active on-click children] :rest props}]
  (let [tag-class (stl/css-case :change-tag true
                                :is-active is-active
                                :is-button (some? on-click))
        props     (mf/spread-props props
                                   {:class [class (stl/css-case :change-box true
                                                                :is-active is-active
                                                                :tone-success (= tone "success")
                                                                :tone-error (= tone "error")
                                                                :tone-warning (= tone "warning"))]
                                    :style (screen-rect rect zoom)})]
    [:> :div props
     (if (some? on-click)
       [:button {:class tag-class :on-click on-click} label]
       [:span {:class tag-class} label])
     children]))

(mf/defc navigator*
  "A bar over the canvas that goes through a list one entry at a time:
  the arrows, the place in the list and, after them, what the entry is
  and what can be done with it (`children`). It stands over the bar at
  the bottom of the canvas, in the middle of the canvas the rulers leave
  as that bar is, and over its strip when that bar is folded
  (`is-lowered`). Its border is blue, unless a `tone` (\"warning\" or
  \"neutral\") says otherwise."
  [{:keys [class index total tone is-lowered on-previous on-next children] :rest props}]
  (let [rulers? (mf/deref refs/rulers?)
        props (mf/spread-props props
                               {:class [class (stl/css-case :navigator true
                                                            :has-rulers rulers?
                                                            :is-lowered is-lowered
                                                            :tone-warning (= tone "warning")
                                                            :tone-neutral (= tone "neutral"))]
                                :role "region"})]
    [:> :div props
     ;; the arrows hold the place in the list between them
     [:div {:class (stl/css :navigator-steps)}
      [:> icon-button* {:variant "ghost"
                        :icon i/arrow-left
                        :aria-label (tr "workspace.review.changes.previous")
                        :disabled (zero? total)
                        :on-click on-previous}]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :navigator-count)}
       (tr "workspace.review.changes.position" (if (pos? total) (inc index) 0) total)]
      [:> icon-button* {:variant "ghost"
                        :icon i/arrow-right
                        :aria-label (tr "workspace.review.changes.next")
                        :disabled (zero? total)
                        :on-click on-next}]]
     children]))

(mf/defc navigator-text*
  "What the entry of a `navigator*` is: its `name` and a `detail`."
  [{:keys [name detail]}]
  [:> text* {:as "span" :typography t/body-small :class (stl/css :navigator-text)}
   (when name
     [:strong {:class (stl/css :navigator-name)} name])
   (when name " ")
   detail])

(mf/defc navigator-separator*
  []
  [:div {:class (stl/css :navigator-separator)}])

(mf/defc changes-navigator*
  "The bar that goes through the changes shown on the canvas."
  {::mf/private true}
  [{:keys [entry index total]}]
  (let [profiles (mf/deref refs/profiles)

        on-prev  (mf/use-fn (mf/deps index) #(st/emit! (dwrv/show-change (dec index))))
        on-next  (mf/use-fn (mf/deps index) #(st/emit! (dwrv/show-change (inc index))))
        on-back  (mf/use-fn #(st/emit! (dwrv/back-to-details)))
        on-close (mf/use-fn #(st/emit! (dwrv/hide-changes)))]

    [:> navigator* {:index index
                    :total total
                    :aria-label (tr "workspace.review.changes.label")
                    :on-previous on-prev
                    :on-next on-next}
     (if (some? entry)
       [:> navigator-text* {:name (:name entry)
                            :detail (tr "workspace.review.changes.by"
                                        (rvc/change-kind-label-lower (:kind entry))
                                        (rvc/profile-name profiles (:profile-id entry))
                                        (ct/format-inst (:changed-at entry) :localized-time))}]
       [:> navigator-text* {:detail (tr "workspace.review.changes.none")}])
     [:> navigator-separator* {}]
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
       (let [shape   (get objects (:id entry))
             kind    (:kind entry)
             active? (= (::d/index entry) index)]
         (when (and (= page-id (:page-id entry))
                    (some? shape)
                    (not (cfh/root? shape)))
           [:> change-box* {:key (dm/str (:id entry))
                            :class (stl/css :review-change)
                            :rect (:selrect shape)
                            :zoom zoom
                            :tone (when (= kind :added) "success")
                            :label (if active?
                                     (tr "workspace.review.text-and-detail"
                                         (:name shape) (rvc/change-kind-label kind))
                                     (rvc/change-kind-label kind))
                            :is-active active?}])))]))

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
               :style (viewbox-offset vbox zoom)}
         [:> change-box* {:rect (:selrect hover)
                          :zoom zoom
                          :class (stl/css :is-pick)
                          :label (tr "workspace.tasks.relink.link-to" (:name hover))
                          :is-active true}]])]
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
                 :style (viewbox-offset vbox zoom)}
           [:> change-boxes* {:entries entries
                              :zoom zoom
                              :page-id page-id
                              :index index}]]
          [:> changes-navigator* {:entry (get entries index)
                                  :index index
                                  :total (count entries)}]])
       (when (some? relink)
         [:> relink-overlay* {:task relink :vbox vbox :zoom zoom}])])))
