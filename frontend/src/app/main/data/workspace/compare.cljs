;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.compare
  "Data layer of the compare mode: the open branch against main, on the
  canvas. The left sidebar lists the changes, the right one shows the
  change chosen, and the canvas marks them where they are. The conflicts
  of a merge or an update are resolved in the same mode.

  `:workspace-compare` is the mode, and its presence makes the workspace
  read-only (`app.main.data.changes/commit-changes`):

    {:branch    the branch row compared (`:id`, `:name`, `:branch-file-id`)
     :phase     :compare | :resolve
     :view      :highlight | :side
     :hidden    true while the changes are hidden on the canvas
     :selected  the key of the change or conflict chosen (`item-key`)
     :attr      the property in turn while resolving a conflict
     :preview   the side (`:main`, `:branch`) of that property the canvas
                tries out while the pointer is on its option
     :peek      true while the canvas shows main in place of the branch
     :filter    :all | :layers | :library | :left-out
     :collapsed the ids of the groups folded in the list
     :counts    how many changes each direction lists, once it loaded
     :other-changes the changes of the direction the diff does not list,
                once they loaded (`watch-events`): main is the branch
                without its own changes and with the ones main made}

  The diff itself, its direction, the per-change selection, the review
  marks and the conflict choices stay in `:workspace-branch-diff`
  (`app.main.data.workspace.branches`)."
  (:require
   [app.common.data :as d]
   [app.common.geom.align :as gal]
   [app.main.data.helpers :as dsh]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.drawing.common :as dwdc]
   [app.main.data.workspace.review :as dwrv]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.viewport-wasm :as dwvw]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def views
  "The ways the canvas shows the changes: marked on the branch
  (`:highlight`), or with main beside it (`:side`), main on the first
  half of the canvas and the branch on the second."
  #{:highlight :side})

(def filters
  #{:all :layers :library :left-out})

(defn comparing?
  [state]
  (some? (:workspace-compare state)))

;; --- Entries

(def item-key
  "The key a change or a conflict is chosen and marked as reviewed by
  (`dwb/item-key`)."
  dwb/item-key)

(defn get-conflicts
  "The conflicts to resolve for the branch `branch-id`. The set a merge or
  an update returned (`attempt`, the `:workspace-branch-conflicts` entry)
  wins over the one of the diff: it is the very set the command matches
  the resolutions against, and the diff can list another one."
  [attempt diff-state branch-id]
  (if (and (= branch-id (:branch-id attempt)) (seq (:conflicts attempt)))
    (:conflicts attempt)
    (get-in diff-state [:diff :conflicts])))

(defn item-rect
  "Where the layer of entry `item` is on its page, as a rect: its place in
  `objects` (the page as the open file has it), or the place the diff
  gives for a layer the open file lacks. Nil for an entry that is no
  layer or has no place."
  [objects item]
  (when (= :shape (:kind item))
    (let [shape (or (get objects (:id item))
                    (:shape item)
                    (:branch item)
                    (:main item))
          {:keys [x y width height]} (or (:selrect shape) shape)]
      (when (and (number? x) (number? y) (number? width) (number? height))
        {:x x :y y :width width :height height}))))

(defn- find-item
  [state item-key']
  (let [diff-st (:workspace-branch-diff state)
        attempt (:workspace-branch-conflicts state)]
    (->> (concat (get-in diff-st [:diff :changes])
                 (get-conflicts attempt diff-st (:branch-id diff-st)))
         (d/seek #(= item-key' (item-key %))))))

;; --- Viewport

(defn- side-view?
  [state]
  (= :side (get-in state [:workspace-compare :view])))

(defn- centered-vbox
  "`vbox` with `rect` in the middle of the canvas, or of its second half
  when main takes the first one (`side?`)."
  [vbox {:keys [x y width height]} side?]
  (assoc vbox
         :x (- (+ x (/ width 2)) (* (:width vbox) (if side? 0.75 0.5)))
         :y (- (+ y (/ height 2)) (/ (:height vbox) 2))))

(defn- center-on-rect
  "Take the viewport to `rect`, at the zoom it has."
  [rect]
  (ptk/reify ::center-on-rect
    ptk/UpdateEvent
    (update [_ state]
      (if (dwvw/render-context-lost? state)
        state
        (update-in state [:workspace-local :vbox] centered-vbox rect (side-view? state))))

    ;; the WASM render keeps its own view: without this the canvas stays
    ;; where it was and the marks, which follow the store, go alone
    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(defn- go-to-item
  "Take the canvas to the layer of `item`: to its page first when it is on
  another one."
  [item]
  (ptk/reify ::go-to-item
    ptk/WatchEvent
    (watch [_ state _]
      (let [page-id (:page-id item)
            rect    (item-rect (dsh/lookup-page-objects state page-id) item)]
        (if (nil? rect)
          (rx/empty)
          (rx/concat
           (if (not= page-id (:current-page-id state))
             (rx/of (rt/nav :workspace (assoc (rt/get-params state) :page-id page-id)
                            {::rt/replace true}))
             (rx/empty))
           (->> (rx/of (center-on-rect rect))
                (rx/observe-on :async))))))))

;; --- Entering and leaving

(defn enter
  "Compare `branch` with main on the canvas. The mode only opens on the
  branch itself: with another file open (main included) nothing happens."
  [branch]
  (assert (uuid? (:id branch)) "expected a branch row with a valid `:id`")
  (ptk/reify ::enter
    ptk/UpdateEvent
    (update [_ state]
      (if (= (:branch-file-id branch) (:current-file-id state))
        (assoc state :workspace-compare
               {:branch    (select-keys branch [:id :name :branch-file-id])
                :phase     :compare
                :view      :highlight
                :hidden    false
                :selected  nil
                :attr      nil
                :filter    :all
                :collapsed #{}})
        state))

    ptk/WatchEvent
    (watch [_ state _]
      (if (comparing? state)
        (rx/of (modal/hide)
               (dwdc/clear-drawing)
               (dws/deselect-all true)
               ;; the changes of a design review give way to these ones
               (dwrv/hide-changes)
               (dwb/fetch-branch-diff (:id branch)))
        (rx/empty)))))

(defn exit
  "Leave the compare mode. The file was never touched, so there is
  nothing to restore: the per-change selection and an unresolved merge or
  update attempt are dropped."
  []
  (ptk/reify ::exit
    ptk/UpdateEvent
    (update [_ state]
      (dissoc state :workspace-compare))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwb/clear-diff-excluded)
             (dwb/close-conflict-resolutions)
             (dwdc/clear-drawing)))))

;; --- What the mode shows

(defn set-direction
  "Show the changes of the branch (`:branch->main`) or the ones main has
  and the branch lacks (`:main->branch`)."
  [direction]
  (assert (contains? #{:branch->main :main->branch} direction) "expected a direction")
  (ptk/reify ::set-direction
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc
                     :phase :compare
                     :selected nil
                     :attr nil
                     :filter :all))

    ptk/WatchEvent
    (watch [_ state _]
      (if-let [branch-id (get-in state [:workspace-compare :branch :id])]
        (rx/of (dwb/fetch-branch-diff branch-id direction))
        (rx/empty)))))

(defn set-view
  [view]
  (assert (contains? views view) "expected a view")
  (ptk/reify ::set-view
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :view view :hidden false))))

(defn toggle-hidden
  "Hide the changes on the canvas, or show them again."
  []
  (ptk/reify ::toggle-hidden
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare update :hidden not))))

(defn set-filter
  "Keep in the list the changes `value` names (one of `filters`)."
  [value]
  (assert (contains? filters value) "expected a filter")
  (ptk/reify ::set-filter
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :filter value))))

(defn set-count
  "Remember that `direction` lists `total` changes, so its tab can say so
  while the other direction is the one shown."
  [direction total]
  (ptk/reify ::set-count
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc-in [:counts direction] total))))

(defn toggle-group
  "Fold the group `id` of the list, or unfold it."
  [id]
  (ptk/reify ::toggle-group
    ptk/UpdateEvent
    (update [_ state]
      (d/update-in-when state [:workspace-compare :collapsed]
                        #(if (contains? % id) (disj % id) (conj % id))))))

(defn select
  "Choose the change or conflict `item-key`. The canvas goes to its layer,
  on whatever page it is, unless `travel` is false: a change chosen on
  the canvas is already in sight."
  ([item-key] (select item-key nil))
  ([item-key {:keys [travel] :or {travel true}}]
   (ptk/reify ::select
     ptk/UpdateEvent
     (update [_ state]
       (d/update-when state :workspace-compare assoc
                      :selected item-key
                      :attr nil
                      :preview nil))

     ptk/WatchEvent
     (watch [_ state _]
       (let [item (when travel (find-item state item-key))]
         ;; the detail of the change takes the right sidebar back from
         ;; the comments, which a commenting tool had put there
         (if (some? item)
           (rx/of (dwdc/clear-drawing)
                  (go-to-item item))
           (rx/of (dwdc/clear-drawing))))))))

(defn toggle-selected
  "Choose the change or conflict `item-key`, or none when it is the one
  chosen: with none chosen the canvas marks them all again."
  [item-key]
  (ptk/reify ::toggle-selected
    ptk/WatchEvent
    (watch [_ state _]
      (rx/of (select (when (not= item-key (get-in state [:workspace-compare :selected]))
                       item-key))))))

(defn fit-selected
  "Zoom to the layer of the change or conflict chosen, as the workspace
  does with the selection: the layer is not selected here, so that
  shortcut finds nothing. Main beside the branch leaves half the canvas
  to fit it in."
  []
  (ptk/reify ::fit-selected
    ptk/UpdateEvent
    (update [_ state]
      (let [item  (find-item state (get-in state [:workspace-compare :selected]))
            rect  (when (= (:page-id item) (:current-page-id state))
                    (item-rect (dsh/lookup-page-objects state) item))
            side? (side-view? state)]
        (if (or (nil? rect) (dwvw/render-context-lost? state))
          state
          (update state :workspace-local
                  (fn [{:keys [vport] :as local}]
                    (let [room  (cond-> vport side? (update :width / 2))
                          srect (gal/adjust-to-viewport room rect {:padding 40 :min-zoom 0.01})
                          zoom  (/ (:width room) (:width srect))
                          vbox  (assoc (:vbox local)
                                       :width (/ (:width vport) zoom)
                                       :height (/ (:height vport) zoom))]
                      (-> local
                          (assoc :zoom zoom)
                          (assoc :zoom-inverse (/ 1 zoom))
                          (assoc :vbox (centered-vbox vbox rect side?)))))))))

    ptk/EffectEvent
    (effect [_ state _]
      (dwvw/maybe-sync-workspace-local-viewport! state))))

(defn set-attr
  "Take the property `attr` of the conflict chosen as the one in turn."
  [attr]
  (ptk/reify ::set-attr
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :attr attr))))

(defn set-preview
  "Try `side` out on the canvas for the property in turn of the conflict
  chosen, or stop trying (nil). Nothing is chosen by it."
  [side]
  (ptk/reify ::set-preview
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :preview side))))

(defn set-peek
  "Show main on the canvas in place of the branch, or the branch again."
  [peek]
  (ptk/reify ::set-peek
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :peek (boolean peek)))))

(defn edit-layer
  "Leave the compare mode with the layer of `item` selected and in sight."
  [item]
  (ptk/reify ::edit-layer
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (rx/of (exit)
              (go-to-item item))
       (->> (rx/of (dws/select-shape (:id item)))
            (rx/observe-on :async))))))

;; --- Resolving conflicts

(defn start-resolve
  "Resolve the conflicts of the comparison on the canvas. With the set a
  merge or an update just returned in the store
  (`dwb/open-conflict-resolutions`), those are the ones resolved, in the
  direction of that attempt; with the branch file not open yet, it opens
  first and the mode follows (`watch-events`)."
  []
  (ptk/reify ::start-resolve
    ptk/UpdateEvent
    (update [_ state]
      (let [attempt (:workspace-branch-conflicts state)
            branch  (or (get-in state [:workspace-compare :branch])
                        (:branch attempt))]
        (if (= (:branch-file-id branch) (:current-file-id state))
          (-> state
              (d/update-when :workspace-branch-conflicts dissoc :pending)
              (update :workspace-compare
                      (fn [mode]
                        (merge {:view :highlight :hidden false :filter :all :collapsed #{}}
                               mode
                               {:branch   (select-keys branch [:id :name :branch-file-id])
                                :phase    :resolve
                                :selected nil
                                :attr     nil}))))
          ;; the conflicts wait for their branch file to open
          (d/update-when state :workspace-branch-conflicts assoc :pending true))))

    ptk/WatchEvent
    (watch [_ state _]
      (let [attempt   (:workspace-branch-conflicts state)
            diff-st   (:workspace-branch-diff state)
            branch    (get-in state [:workspace-compare :branch])
            direction (case (:mode attempt)
                        :update :main->branch
                        :merge  :branch->main
                        (:direction diff-st :branch->main))]
        (cond
          ;; the conflicts belong to a branch that is not the open file
          (and (nil? branch) (uuid? (get-in attempt [:branch :branch-file-id])))
          (rx/of (dwb/open-branch (get-in attempt [:branch :branch-file-id])))

          (nil? branch)
          (rx/empty)

          ;; the diff backs the sides of each conflict, so it is the one
          ;; of the direction resolved
          (or (not= (:id branch) (:branch-id diff-st))
              (not= direction (:direction diff-st)))
          (rx/of (modal/hide)
                 (dwdc/clear-drawing)
                 (dws/deselect-all true)
                 (dwb/fetch-branch-diff (:id branch) direction))

          :else
          (rx/of (modal/hide)))))))

(defn back-to-compare
  "Leave the conflicts unresolved and go back to the list of changes. The
  choices made so far are kept; the merge or update attempt is not."
  []
  (ptk/reify ::back-to-compare
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc
                     :phase :compare
                     :selected nil
                     :attr nil))

    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwb/close-conflict-resolutions)))))

(defn apply-resolutions
  "Merge or update with the choices made for every conflict. The
  per-change selection and the comment of the merge travel again: the
  ones of the attempt that returned the conflicts, or the selection of
  the comparison when the user came from it."
  []
  (ptk/reify ::apply-resolutions
    ptk/WatchEvent
    (watch [_ state _]
      (let [diff-st   (:workspace-branch-diff state)
            attempt   (:workspace-branch-conflicts state)
            branch    (get-in state [:workspace-compare :branch])
            attempt   (when (= (:id branch) (:branch-id attempt)) attempt)
            conflicts (get-conflicts attempt diff-st (:id branch))
            ids       (into #{} (map :id) conflicts)
            res       (into {}
                            (filter (fn [[id _]] (contains? ids id)))
                            (:resolutions diff-st))
            excluded  (if (some? attempt)
                        (:excluded attempt)
                        (dwb/diff-excluded diff-st))]
        (if (= :main->branch (:direction diff-st))
          (rx/of (dwb/update-branch-from-main branch {:resolutions res
                                                      :excluded excluded}))
          (rx/of (dwb/merge-branch branch {:resolutions res
                                           :comment (:comment attempt)
                                           :excluded excluded})))))))

(defn- resume-resolve
  "Start resolving the conflicts that waited for the branch file to open."
  []
  (ptk/reify ::resume-resolve
    ptk/WatchEvent
    (watch [_ state _]
      (let [attempt (:workspace-branch-conflicts state)]
        (if (and (:pending attempt)
                 (= (get-in attempt [:branch :branch-file-id]) (:current-file-id state)))
          (rx/of (start-resolve))
          (rx/empty))))))

(defn- set-other-changes
  [changes]
  (ptk/reify ::set-other-changes
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare assoc :other-changes changes))))

(defn- fetch-other-changes
  "Load the changes of the direction the diff of the mode does not list.
  The diff of a direction carries the changes of one side alone, and main
  takes both to put together: the layers the branch changed as they
  were, and the ones main changed since as they are now. A failure
  leaves main without the second ones."
  []
  (ptk/reify ::fetch-other-changes
    ptk/UpdateEvent
    (update [_ state]
      (d/update-when state :workspace-compare dissoc :other-changes))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [branch-id (get-in state [:workspace-compare :branch :id])
            diff-st   (:workspace-branch-diff state)
            direction (if (= :main->branch (:direction diff-st))
                        :branch->main
                        :main->branch)]
        (if (and (some? branch-id) (= branch-id (:branch-id diff-st)))
          (->> (rp/cmd! :get-branch-diff {:branch-id branch-id :direction direction})
               (rx/map #(set-other-changes (:changes %)))
               (rx/catch (fn [_] (rx/empty)))
               (rx/take-until
                (rx/filter #(or (ptk/type? ::fetch-other-changes %)
                                (ptk/type? ::exit %))
                           stream)))
          (rx/empty))))))

(defn watch-events
  "What the compare mode follows from outside it, until `stopper` emits.

  Every diff asked for brings the changes of its other direction with it
  (`fetch-other-changes`).

  A merge or an update that returns conflicts takes the user to resolve
  them. The command can run with main open (from the list of branches),
  so the conflicts wait in the store while the branch file opens: once
  its context loads, the mode starts there."
  [stopper]
  (ptk/reify ::watch-events
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> (rx/merge
            (->> stream
                 (rx/filter (ptk/type? ::dwb/fetch-branch-diff))
                 (rx/map (fn [_] (fetch-other-changes))))
            (->> stream
                 (rx/filter (ptk/type? ::dwb/open-conflict-resolutions))
                 (rx/map (fn [_] (start-resolve))))
            (->> stream
                 (rx/filter (ptk/type? ::dwb/set-branch-context))
                 (rx/take 1)
                 (rx/map (fn [_] (resume-resolve)))))
           (rx/take-until stopper)))))
