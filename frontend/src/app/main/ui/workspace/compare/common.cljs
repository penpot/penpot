;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.compare.common
  "What every panel of the compare mode reads and does: the comparison
  as the store has it, and the actions the list, the navigator, the
  banner and the popover share."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace.branches :as dwb]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.sides :as brs]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn use-comparison
  "The comparison of the compare mode, put together from the store:

    `:mode`        the `:workspace-compare` entry, and from it `:branch`,
                   `:phase`, `:selected`, `:hovered` and `:attr`;
    `:status`      of the diff (`:loading`, `:loaded`, `:error`);
    `:incoming`    true when the changes listed are main's;
    `:excluded`, `:reviewed`, `:resolutions`  the marks on the diff;
    `:diff`, `:data`, `:copies`  what the detail and the export read;
    `:model`       the list of changes (`brc/change-model`);
    `:conflicts`   the list of conflicts (`brc/conflict-model`);
    `:rows`        the rows of the phase, in the order of its list;
    `:item`        the change or conflict chosen, and `:res` its
                   resolution when it is a conflict."
  []
  (let [mode      (mf/deref refs/compare-mode)
        diff-st   (mf/deref refs/branch-diff)
        attempt   (mf/deref refs/branch-conflicts)
        data      (mf/deref refs/workspace-data)

        {:keys [branch phase selected]} mode
        {:keys [status diff resolutions]} diff-st
        incoming? (= :source->branch (:direction diff-st))
        excluded  (dwb/diff-excluded diff-st)
        reviewed  (or (:reviewed diff-st) #{})
        active    (:filter mode)

        model     (mf/with-memo [diff active excluded data]
                    (brc/change-model diff {:filter active
                                            :excluded excluded
                                            :pages-index (:pages-index data)
                                            :pages (:pages data)}))
        conflicts (mf/with-memo [attempt diff-st branch resolutions]
                    (brc/conflict-model (dwcp/get-conflicts attempt diff-st (:id branch))
                                        (or resolutions {})))
        rows      (if (= phase :resolve) (:rows conflicts) (:rows model))
        item      (mf/with-memo [rows selected]
                    (some #(when (= selected (dwcp/item-key %)) %) rows))]
    {:mode        mode
     :branch      branch
     :phase       phase
     :selected    selected
     :hovered     (:hovered mode)
     :hovered-from (:hovered-from mode)
     :attr        (:attr mode)
     :status      status
     :incoming    incoming?
     :excluded    excluded
     :reviewed    reviewed
     :resolutions (or resolutions {})
     :diff        diff
     :data        data
     :copies      (:copies diff)
     :model       model
     :conflicts   conflicts
     :rows        rows
     :item        item
     :res         (get resolutions (:id item))}))

;; --- What the canvas shows

(defn main-objects
  "The layers of page `page-id` as main has them, from `objects`, the
  page as the open branch has it. `items` are the entries of the diff
  listed, main's own when `incoming`, and `other` the changes of its
  other direction (`:other-changes` of the mode): main lacks what the
  branch changed and has what main itself changed."
  [objects page-id items other incoming]
  (let [on-page (fn [items] (filterv #(= page-id (:page-id %)) items))
        [own others] (if incoming [:source :branch] [:branch :source])]
    (-> objects
        (brs/side-objects (on-page items) :source :branch own)
        (brs/side-objects (on-page other) :source :branch others))))

(defn main-extent
  "The rect that holds every layer of `main` (`main-objects`), nil for an
  empty page."
  [main]
  (let [shapes (cfh/get-immediate-children main)]
    (when (seq shapes)
      (gsh/shapes->rect shapes))))

(defn- get-center
  [objects id]
  (when-let [{:keys [x y width height]} (get-in objects [id :selrect])]
    {:x (+ x (/ width 2)) :y (+ y (/ height 2))}))

(defn align-offset
  "How far main is moved, as `{:x :y}` in page units, so the layer of
  entry `item` sits in main's half where the canvas has it in its own:
  the centre of the layer in `canvas` (the page as the canvas shows it)
  less its centre in `main`. Nil when there is nothing to line up: no
  entry, an entry that is no layer, a layer one side lacks, or the same
  place on both."
  [canvas main item]
  (when (= :shape (:kind item))
    (let [to   (get-center canvas (:id item))
          from (get-center main (:id item))]
      (when (and (some? to) (some? from) (not= to from))
        {:x (- (:x to) (:x from))
         :y (- (:y to) (:y from))}))))

(defn canvas-objects
  "The layers of the page `page-id` as the canvas shows them in the
  compare mode, from `objects`, the page as the open branch has it. The
  canvas shows what the action on offer leaves:

    - listing main's changes, the branch with the ones kept brought in;
    - resolving, the choices made so far on top of that, with the side
      the pointer tries out (`:preview`) as if it were chosen;
    - while peeking (`:peek`), main in place of the branch.

  Returns `{:kind :objects}` with `:kind` one of `:update`, `:result` or
  `:main`, or nil when the canvas shows the branch as it is."
  [{:keys [mode phase incoming model conflicts resolutions excluded item res attr]} objects page-id]
  (let [on-page?  #(= page-id (:page-id %))
        incoming* (fn [objects]
                    (brs/side-objects objects
                                      (filterv #(and (on-page? %)
                                                     (brc/selectable-item? %)
                                                     (not (contains? excluded (:key %))))
                                               (:items model))
                                      :source :branch :source))
        result
        (cond
          (:hidden mode)
          nil

          (= phase :resolve)
          (let [side (:preview mode)
                res* (cond-> resolutions
                       (and (some? side) (some? item))
                       (assoc (:id item)
                              (if-let [attr (brc/pending-attr item res attr)]
                                (brc/choose-attr item res attr side)
                                side)))]
            {:kind :result
             :objects (brs/resolved-objects (cond-> objects incoming incoming*)
                                            (filterv on-page? (:conflicts conflicts))
                                            res*)})

          incoming
          {:kind :update :objects (incoming* objects)}

          (:peek mode)
          {:kind :main
           :objects (main-objects objects page-id (:items model) (:other-changes mode) false)})]
    (when (and (some? result) (not (identical? objects (:objects result))))
      result)))

(defn chosen-marks
  "The marks of `marks` the canvas shows with the entry `selected`
  chosen: its own, the ones of the layers that use it (`:usage`) and the
  one of the entry whose row is under the pointer (`hovered`), so the
  canvas is not crowded. All of them with none chosen."
  [selected hovered marks]
  (if (some? selected)
    (filterv #(or (= selected (:key %))
                  (= hovered (:key %))
                  (= :usage (:status %)))
             marks)
    marks))

(defn layer-entry
  "The key of the entry of `rows` that answers for the layers `ids` (the
  ones under the pointer) of `objects`: the entry of one of them or of
  the closest layer that holds it, as a change of a board is the change
  of what is in it. With several, the one of the deepest layer."
  [rows objects ids]
  (let [by-layer (into {}
                       (comp (filter #(= :shape (:kind %)))
                             (map (juxt :id dwcp/item-key)))
                       rows)
        ;; up to the root of the page, which is its own parent
        chain    (fn [id]
                   (loop [id id found []]
                     (let [parent-id (:parent-id (get objects id))]
                       (if (or (nil? id) (some #(= id %) found))
                         found
                         (recur (when (not= parent-id id) parent-id)
                                (conj found id))))))
        depth    (fn [id] (count (chain id)))
        holder   (fn [id] (some #(when (contains? by-layer %) %) (chain id)))]
    (when (seq by-layer)
      (some->> (keep holder ids)
               (seq)
               (apply max-key depth)
               (get by-layer)))))

;; --- Moving through the list

(defn select-step
  "Choose the row `offset` rows away from the one chosen."
  [{:keys [rows selected]} offset]
  (when-let [item-key (brc/step rows selected offset)]
    (st/emit! (dwcp/select item-key))))

(defn mark-reviewed
  "Mark the change chosen as reviewed and go to the next one not reviewed
  yet."
  [{:keys [rows selected item reviewed]}]
  (when (some? item)
    (st/emit! (dwb/toggle-diff-reviewed selected true)))
  (when-let [item-key (brc/next-pending rows selected
                                        #(or (bm/settled? %)
                                             (contains? reviewed (dwcp/item-key %))))]
    (st/emit! (dwcp/select item-key))))

(defn toggle-excluded
  "Leave the change chosen out of the merge or update, or take it back
  in, with the layers folded under it."
  [{:keys [item model]}]
  (when (and (some? item) (brc/selectable-item? item))
    (st/emit! (dwb/toggle-diff-excluded
               (brc/row-keys item (get (:cascades model) (dwcp/item-key item)))))))

(defn export-diff
  "Download the diff of the comparison as a JSON file named after the
  branch."
  [{:keys [branch diff]}]
  (let [payload (clj->js {:stats (:stats diff)
                          :changes (:changes diff)
                          :conflicts (:conflicts diff)
                          :copies (:copies diff)})
        text    (js/JSON.stringify payload nil 2)]
    (dom/trigger-download (str (:name branch) "-diff.json")
                          (wapi/create-blob text "application/json"))))

;; --- Deciding a conflict

(defn pick-attr
  "Keep `side` for the property `attr` of the conflict chosen."
  [{:keys [item res]} attr side]
  (when (some? item)
    (st/emit! (dwb/set-conflict-resolutions
               {(:id item) (brc/choose-attr item res attr side)}))))

(defn choose-side
  "Keep `side` for the whole conflict chosen."
  [{:keys [item]} side]
  (when (some? item)
    (st/emit! (dwb/set-conflict-resolution (:id item) side))))

(defn clear-choice
  "Forget the choice made for the conflict chosen: it asks again, with
  the edits one side made alone filled in as at the start."
  [{:keys [item]}]
  (when (some? item)
    (st/emit! (dwb/clear-conflict-resolution (:id item))
              (dwb/set-conflict-resolutions
               (if (brc/per-attr? item)
                 {(:id item) (brc/prefill-resolution item)}
                 {})))))

(defn keep-all
  "Keep `side` for every conflict; the edits one side made alone stay
  with the side that made them."
  [{:keys [conflicts]} side]
  (st/emit! (dwb/set-conflict-resolutions
             (into {}
                   (map (fn [conflict] [(:id conflict) (brc/choose-side conflict side)]))
                   (:conflicts conflicts)))))

(defn next-pending
  "Go to the next thing to decide: the next property without a choice of
  the conflict chosen, or the next conflict that waits for one."
  [{:keys [rows selected item res attr resolutions]}]
  (let [current (when (some? item) (brc/pending-attr item res attr))
        another (when (some? item)
                  (->> (brc/conflicting-attrs (brc/conflict-attr-rows item))
                       (remove #{current})
                       (some #(when (nil? (brc/attr-choice res %)) %))))]
    (if (and (some? another) (brc/per-attr? item))
      (st/emit! (dwcp/set-attr another))
      (when-let [item-key (brc/next-pending rows selected
                                            #(bm/conflict-resolved? % (get resolutions (:id %))))]
        (st/emit! (dwcp/select item-key))))))

;; --- The bar at the bottom of the canvas

(defn- on-exit
  []
  (st/emit! (dwcp/exit)))

(defn- on-back
  []
  (st/emit! (dwcp/back-to-compare)))

(defn- on-resolve
  []
  (st/emit! (dwcp/start-resolve)))

(defn- on-apply
  []
  (st/emit! (dwcp/apply-resolutions)))

(defn- compare-bar
  [{:keys [branch status incoming model reviewed excluded]}]
  (let [{:keys [counted decisions kept left-out]} model
        loaded?    (= status :loaded)
        total      (count counted)
        reviewed-n (count (filter #(contains? reviewed (dwcp/item-key %)) counted))]
    {:icon   (if incoming i/arrow-down i/git-compare-arrows)
     :title  (if (and incoming loaded?)
               (tr "workspace.branches.banner.status.behind" (i18n/c total))
               (tr "workspace.compare.bar.comparing-main"))
     :detail (cond
               (= status :error) (tr "workspace.branches.compare.error")
               (not loaded?)     (tr "workspace.branches.compare.loading")
               :else
               (str/join " - "
                         (remove nil?
                                 [(tr "workspace.branches.compare.footer-changes" (i18n/c total))
                                  (when (pos? left-out)
                                    (tr "workspace.branches.review.footer.left-out" (i18n/c left-out)))
                                  (when (pos? reviewed-n)
                                    (tr "workspace.branches.review.footer.reviewed"
                                        (str reviewed-n) (str total)))])))
     :progress (when loaded?
                 (tr "workspace.branches.review.footer.reviewed" (str reviewed-n) (str total)))
     :exit   {:label (tr "workspace.compare.bar.exit") :icon i/close :on-click on-exit}
     :action (cond
               (not loaded?)
               nil

               (pos? decisions)
               {:label (tr "workspace.branches.review.action.resolve" (i18n/c decisions))
                :icon i/git-merge-conflict
                :on-click on-resolve}

               incoming
               {:label (tr "workspace.branches.review.action.update" (i18n/c kept))
                :icon i/status-update
                :disabled (zero? kept)
                :on-click #(brc/confirm-update! branch excluded)}

               :else
               {:label (tr "workspace.branches.review.action.merge" (i18n/c kept))
                :icon i/git-merge
                :merge true
                :disabled (zero? kept)
                :on-click #(brc/confirm-merge! branch excluded)})}))

(defn- resolve-bar
  [{:keys [incoming conflicts]}]
  (let [total    (count (:conflicts conflicts))
        decided  (count (:decided conflicts))
        done?    (and (pos? total) (= total decided))]
    {:icon    (if done? i/tick i/triangle-alert)
     :warning (not done?)
     :title   (if done?
                (tr "workspace.compare.bar.all-decided")
                (tr "workspace.branches.banner.status.conflicts" (i18n/c total)))
     :detail  (tr "workspace.compare.bar.decided" (str decided) (str total))
     :progress (tr "workspace.compare.bar.decided" (str decided) (str total))
     :exit    {:label (tr "workspace.compare.bar.back") :icon i/arrow-left :on-click on-back}
     :action  {:label (if incoming
                        (tr "workspace.compare.bar.apply-update")
                        (tr "workspace.branches.conflicts.apply"))
               :icon i/git-merge
               :merge (not incoming)
               ;; nothing is applied until every conflict has a choice
               :disabled (not done?)
               :on-click on-apply}}))

(defn bar
  "What the bar at the bottom of the canvas says and offers in the
  compare mode, for the banner that draws it: its `:icon` (a `:warning`
  one while conflicts wait), `:title` and `:detail`, the `:progress` of
  the review or of the decisions, the way out (`:exit`, drawn as its
  `:icon` alone, its `:label` in a tooltip) and the main `:action`, each
  `{:label :on-click}` with `:disabled` and `:icon`. An
  action that merges into main says so (`:merge`), as not everybody may
  merge."
  [comparison]
  (if (= :resolve (:phase comparison))
    (resolve-bar comparison)
    (compare-bar comparison)))
