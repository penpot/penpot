;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.overlays
  "What the compare mode draws over the canvas: a mark on every changed
  layer of the page, the bar that goes through the changes and, while
  resolving, the popover that asks for a choice beside the layer in
  conflict."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.compare.common :as cc]
   [app.main.ui.workspace.compare.side :refer [side-view*]]
   [app.main.ui.workspace.review.overlays :as rvo]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.detail :as brd]
   [app.render-wasm.api :as wasm.api]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

;; --- Marks

(defn- status-tone
  [status]
  (case status
    :added    "success"
    :deleted  "error"
    :conflict "warning"
    :decided  "success"
    nil))

(defn- status-label
  [status]
  (case status
    :added    (tr "workspace.branches.status.added")
    :deleted  (tr "workspace.branches.status.deleted")
    :conflict (tr "workspace.branches.status.conflict")
    :decided  (tr "workspace.branches.resolve.decided")
    (tr "workspace.branches.status.modified")))

(mf/defc mark*
  "The mark of one change. Its tag chooses the change, without moving
  the canvas: it is already in sight."
  {::mf/private true}
  [{:keys [mark zoom is-active children]}]
  (let [{:keys [label status rect]} mark
        mark-key (:key mark)
        usage?   (= :usage status)
        on-click (mf/use-fn
                  (mf/deps mark-key)
                  (fn [event]
                    (dom/stop-propagation event)
                    (st/emit! (dwcp/select mark-key {:travel false}))))]
    [:> rvo/change-box* {:rect rect
                         :zoom zoom
                         :tone (status-tone status)
                         :label (cond
                                  usage?    label
                                  is-active (tr "workspace.review.text-and-detail"
                                                label (status-label status))
                                  :else     (status-label status))
                         :is-active is-active
                         ;; a layer that only uses the change is no row
                         ;; of the list
                         :on-click (when-not usage? on-click)}
     children]))

;; --- The choice of a conflict

(def ^:private popover-width
  "The width of the popover in pixels, as its stylesheet sets it: the
  side it opens on is decided from it."
  240)

(mf/defc conflict-popover*
  "The choice of the conflict chosen, beside its layer: one property at a
  time for a conflict that splits by property, the two texts for a text
  conflict, the two versions otherwise. It opens after the layer, or
  before it when the canvas has no room left there."
  {::mf/private true}
  [{:keys [comparison rect vbox vport zoom is-review can-preview]}]
  (let [{:keys [item res attr branch incoming]} comparison
        attr      (brc/pending-attr item res attr)
        text?     (and (= attr :content) (brc/text-conflict? item))
        branch-nm (:name branch)
        room      (- (:width vport)
                     (* (- (+ (:x rect) (:width rect)) (:x vbox)) zoom))
        before?   (< room (+ popover-width 16))
        on-pick   (mf/use-fn (mf/deps comparison) #(cc/pick-attr comparison %1 %2))
        on-choose (mf/use-fn (mf/deps comparison) #(cc/choose-side comparison %))
        on-preview (mf/use-fn #(st/emit! (dwcp/set-preview %)))]
    [:div {:class (stl/css-case :popover true :is-before before?)
           :role "dialog"
           :aria-label (tr "workspace.branches.status.conflict")
           ;; the canvas under it must not take its clicks
           :on-pointer-down dom/stop-propagation
           :on-click dom/stop-propagation}
     [:> heading* {:level 3 :typography t/body-small :class (stl/css :popover-title)}
      (if (some? attr)
        (brc/attr-label attr)
        (:label item))]
     [:> text* {:as "p" :typography t/body-small :class (stl/css :popover-text)}
      (cond
        text?        (tr "workspace.compare.popover.text")
        (some? attr) (tr "workspace.compare.popover.attr")
        :else        (tr "workspace.branches.resolve.intro-whole"))]
     (cond
       text?
       [:> brd/text-options* {:conflict item :res res
                              :branch-name branch-nm :is-review is-review
                              :on-pick on-pick
                              :on-preview on-preview}]

       (some? attr)
       [:> brd/attr-options* {:conflict item :res res :attr attr
                              :branch-name branch-nm :is-review is-review
                              :on-pick on-pick
                              :on-preview on-preview}]

       :else
       [:> brd/whole-options* {:conflict item :res res
                               :branch-name branch-nm :is-review is-review
                               :on-choose on-choose
                               :on-preview on-preview}])
     [:> text* {:as "p" :typography t/body-small :class (stl/css :popover-text)}
      (cond
        can-preview (tr "workspace.compare.popover.hover")
        incoming    (tr "workspace.compare.popover.applies-update")
        :else       (tr "workspace.compare.popover.applies-merge"))]]))

;; --- The bar that goes through the list

(mf/defc compare-navigator*
  {::mf/private true}
  [{:keys [comparison can-peek]}]
  (let [{:keys [rows selected item phase res attr incoming resolutions]} comparison
        resolving? (= phase :resolve)
        index      (or (first (keep-indexed (fn [idx row]
                                              (when (= selected (dwcp/item-key row)) idx))
                                            rows))
                       0)
        resolved?  (and resolving? (some? item) (bm/conflict-resolved? item res))
        all-done?  (and resolving?
                        (every? #(bm/conflict-resolved? % (get resolutions (:id %))) rows))
        cur-attr   (when (and resolving? (some? item)) (brc/pending-attr item res attr))

        on-previous (mf/use-fn (mf/deps comparison) #(cc/select-step comparison -1))
        on-next     (mf/use-fn (mf/deps comparison) #(cc/select-step comparison 1))
        on-mark     (mf/use-fn (mf/deps comparison) #(cc/mark-reviewed comparison))
        on-pending  (mf/use-fn (mf/deps comparison) #(cc/next-pending comparison))
        on-clear    (mf/use-fn (mf/deps comparison) #(cc/clear-choice comparison))

        ;; the bar of the branch folds down to a strip, and this one
        ;; goes down with it
        bottom-bar  (mf/deref refs/bottom-bar)
        folded?     (and (some? bottom-bar) (not= :branch bottom-bar))]
    [:> rvo/navigator* {:class (stl/css :navigator)
                        :index index
                        :is-lowered folded?
                        :total (count rows)
                        :tone (when (and resolving? (not all-done?)) "warning")
                        :aria-label (if resolving?
                                      (tr "workspace.compare.navigator.decisions")
                                      (tr "workspace.compare.navigator.changes"))
                        :on-previous on-previous
                        :on-next on-next}
     [:> rvo/navigator-text*
      {:name (:label item)
       :detail (when (some? item)
                 (cond
                   (and (not resolving?) incoming)
                   (tr "workspace.compare.navigator.in-main" (brc/change-summary item))

                   (and (not resolving?) can-peek)
                   (tr "workspace.compare.navigator.peek" (brc/change-summary item))

                   (not resolving?)
                   (brc/change-summary item)

                   (some? cur-attr)
                   (tr "workspace.branches.resolve.row.both-changed" (brc/attr-label cur-attr))

                   :else
                   (brc/conflict-reason-label (:reason item))))}]
     [:> rvo/navigator-separator* {}]
     (cond
       (not resolving?)
       [:> button* {:variant "ghost" :class (stl/css :navigator-action)
                    :disabled (nil? item) :on-click on-mark}
        (tr "workspace.compare.navigator.mark-reviewed")]

       (and resolved? all-done?)
       [:> button* {:variant "ghost" :class (stl/css :navigator-action) :on-click on-clear}
        (tr "workspace.branches.resolve.change-choice")]

       :else
       [:> button* {:variant "ghost" :class (stl/css :navigator-action) :on-click on-pending}
        (tr "workspace.compare.navigator.next-pending")])]))

;; --- The mode on the canvas

(def ^:private next-key? (kbd/is-key-ignore-case? "j"))
(def ^:private previous-key? (kbd/is-key-ignore-case? "k"))
(def ^:private reviewed-key? (kbd/is-key-ignore-case? "r"))
(def ^:private exclude-key? (kbd/is-key-ignore-case? "x"))
(def ^:private peek-key? (kbd/is-key-ignore-case? "m"))

(defn- fit-key?
  "The key 2, whatever character it types with shift held."
  [event]
  (contains? #{"Digit2" "Numpad2"} (.-code ^js event)))

(defn- typing?
  "Whether a key `event` belongs to a field the user writes in."
  [event]
  (let [target (dom/get-target event)]
    (or (contains? #{"INPUT" "TEXTAREA" "SELECT"} (dom/get-tag-name target))
        (dom/is-content-editable? target))))

(defn- use-shortcuts
  "The keys of the compare mode: Escape leaves it (or goes back from the
  conflicts to the changes), J and K go to the next and the previous
  row, R marks the change as reviewed, X leaves it out or takes it back
  in, and M shows main for as long as it is held. A dialog, a field or a
  tool in use keeps its own keys."
  [comparison]
  (let [comparison* (mf/use-ref comparison)]
    (mf/set-ref-val! comparison* comparison)
    (mf/with-effect []
      (let [on-key
            (fn [event]
              (let [comparison (mf/ref-val comparison*)
                    state      @st/state
                    resolving? (= :resolve (:phase comparison))]
                (when-not (or (typing? event)
                              (kbd/mod? event)
                              (kbd/alt? event)
                              (some? (::modal/modal state))
                              (some? (get-in state [:workspace-drawing :tool])))
                  (cond
                    (kbd/esc? event)
                    (st/emit! (if resolving? (dwcp/back-to-compare) (dwcp/exit)))

                    ;; the layer chosen is not selected: the key that
                    ;; zooms to the selection zooms to it
                    (and (fit-key? event) (kbd/shift? event))
                    (st/emit! (dwcp/fit-selected))

                    (kbd/shift? event)
                    nil

                    (next-key? event)     (cc/select-step comparison 1)
                    (previous-key? event) (cc/select-step comparison -1)

                    (and (reviewed-key? event) (not resolving?))
                    (cc/mark-reviewed comparison)

                    (and (exclude-key? event) (not resolving?))
                    (cc/toggle-excluded comparison)

                    (and (peek-key? event) (not resolving?) (not (.-repeat ^js event)))
                    (st/emit! (dwcp/set-peek true))))))

            on-key-up
            (fn [event]
              (when (peek-key? event)
                (st/emit! (dwcp/set-peek false))))]
        (.addEventListener js/document "keydown" on-key)
        (.addEventListener js/document "keyup" on-key-up)
        (fn []
          (.removeEventListener js/document "keydown" on-key)
          (.removeEventListener js/document "keyup" on-key-up))))))

(defn- use-canvas-projection
  "Draw the layers of `projected` that differ from `objects` (the page as
  the open file has it) on the canvas, and the ones of `objects` again
  when the projection changes or ends. Only the render hears of it: the
  file in the store is never touched. A layer the projection lacks goes
  with the children of its parent, and one only the projection has
  comes with them."
  [objects projected]
  (mf/with-effect [objects projected]
    (when (and (some? projected) (wasm.api/initialized?))
      (let [ids (into []
                      (filter #(not (identical? (get objects %) (get projected %))))
                      (distinct (concat (keys projected) (keys objects))))]
        (wasm.api/process-objects (into [] (keep #(get projected %)) ids))
        (wasm.api/request-render "compare-projection")
        (fn []
          (when (wasm.api/initialized?)
            (wasm.api/process-objects (into [] (keep #(get objects %)) ids))
            (wasm.api/request-render "compare-projection")))))))

(mf/defc compare-overlays*
  [{:keys [vbox vport zoom page-id show-rulers hover-ids]}]
  (let [comparison (cc/use-comparison)
        objects    (mf/deref refs/workspace-page-objects)
        review?    (some? (mf/deref refs/pull-request-preview))

        {:keys [mode phase status incoming selected hovered item rows model
                conflicts excluded resolutions data res attr]} comparison
        resolving? (= phase :resolve)
        hidden?    (:hidden mode)
        loaded?    (= status :loaded)
        direction  (if incoming :main->branch :branch->main)
        total      (get-in model [:counts :all])
        first-key  (some-> (first rows) dwcp/item-key)
        ;; the entry of the layer under the pointer, while the canvas
        ;; answers for the layers with a change (`hover-ids`)
        pointed    (mf/with-memo [rows objects hover-ids]
                     (cc/layer-entry rows objects hover-ids))

        ;; only the WASM render can draw a page the file does not hold
        wasm?      (features/use-feature "render-wasm/v1")
        ;; from what it reads alone: the comparison is put together on
        ;; every render, and each projection is drawn again by the render
        projection (mf/with-memo [wasm? objects page-id phase incoming model conflicts
                                  resolutions excluded item res attr
                                  hidden? (:preview mode) (:peek mode) (:other-changes mode)]
                     (when wasm?
                       (cc/canvas-objects comparison objects page-id)))
        live       objects
        side?      (and wasm? (= :side (:view mode)))
        objects    (or (:objects projection) objects)
        ;; kept the same between renders: the side view takes its pictures
        ;; of main again when they change
        page-items (mf/with-memo [model page-id]
                     (filterv #(= page-id (:page-id %)) (:items model)))

        ;; every change is marked until one is chosen; then its mark
        ;; stands alone, with the layers that use it when it is an asset
        marks
        (mf/with-memo [resolving? model conflicts resolutions excluded item objects page-id data selected hovered]
          (cc/chosen-marks
           selected
           hovered
           (if resolving?
             (brc/change-marks (:conflicts conflicts)
                               {:page-id page-id
                                :objects objects
                                :status-of #(if (bm/conflict-resolved? % (get resolutions (:id %)))
                                              :decided
                                              :conflict)})
             (cond-> (brc/change-marks (:listed model)
                                       {:page-id page-id
                                        :objects objects
                                        :excluded excluded})
               (and (some? item) (= :library (brc/scope item)))
               (into (brc/usage-marks data item page-id))))))]

    (use-shortcuts comparison)

    (mf/with-effect [pointed]
      (st/emit! (dwcp/set-hovered pointed :canvas)))
    (use-canvas-projection live (:objects projection))

    ;; each direction remembers how many changes it lists, for its tab
    (mf/with-effect [loaded? direction total]
      (when (and loaded? (not resolving?))
        (st/emit! (dwcp/set-count direction total))))

    ;; the edits one side made alone are not in doubt: they are filled in
    ;; from that side, so only the conflicting properties wait
    (mf/with-effect [resolving? (:conflicts conflicts)]
      (when resolving?
        (let [seed (brc/prefill-resolutions (:conflicts conflicts) resolutions)]
          (when (seq seed)
            (st/emit! (dwb/set-conflict-resolutions seed))))))

    ;; a list opens on its first row, with the canvas on its layer. Only
    ;; when the list changes: a row the user lets go stays let go, and
    ;; the canvas marks every change again
    (mf/with-effect [resolving? first-key]
      (when (and (nil? selected) (some? first-key))
        (st/emit! (dwcp/select first-key))))

    [:div {:class (stl/css :overlays)
           :style #js {:width (dm/str (:width vport) "px")
                       :height (dm/str (:height vport) "px")}}
     (when-not hidden?
       [:div {:class (stl/css :marks)
              :style (rvo/viewbox-offset vbox zoom)}
        (for [mark marks
              :let [active? (or (= selected (:key mark))
                                (= hovered (:key mark)))]]
          [:> mark* {:key (:key mark)
                     :mark mark
                     :zoom zoom
                     :is-active active?}
           (when (and active? resolving? (some? item))
             [:> conflict-popover* {:comparison comparison
                                    :rect (:rect mark)
                                    :vbox vbox
                                    :vport vport
                                    :zoom zoom
                                    :is-review review?
                                    :can-preview wasm?}])])])
     ;; main beside the branch, over the first half of the canvas
     (when side?
       [:> side-view* {:comparison comparison
                       :objects live
                       :canvas objects
                       :items page-items
                       :listed (if resolving? (:conflicts conflicts) (:listed model))
                       :page-id page-id
                       :background (get-in data [:pages-index page-id :background])
                       :vbox vbox
                       :vport vport
                       :zoom zoom
                       :show-rulers show-rulers}])
     [:div {:class (stl/css :notes)}
      (when (some? projection)
        [:> text* {:as "span" :typography t/body-small :class (stl/css :note)}
         (case (:kind projection)
           :update (tr "workspace.compare.canvas.update" (get-in comparison [:branch :name]))
           :result (tr "workspace.compare.canvas.result")
           :main   (tr "workspace.compare.canvas.main"))])
      (when side?
        [:> text* {:as "span" :typography t/body-small :class (stl/css :note :is-quiet)}
         (tr "workspace.compare.side.linked")])]
     ;; the bar goes through the list from the entry chosen: with none
     ;; chosen the canvas marks them all and the bar has nothing to say
     (when (some? item)
       [:> compare-navigator* {:comparison comparison :can-peek wasm?}])]))
