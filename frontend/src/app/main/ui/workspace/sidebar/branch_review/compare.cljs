;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.compare
  "The compare dialog: what a branch changes, in words. It opens on the
  full list of changes, grouped by page and board, each one with its
  before and after."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.checkbox :refer [checkbox*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.change-row :refer [change-row*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.filter-chip :refer [filter-chip*]]
   [app.main.ui.ds.product.preview-card :refer [preview-card*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.facts :as brf]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private branch-diff
  (l/derived :workspace-branch-diff st/state))

(defn- selectable-item?
  "A row the per-change selection covers: a clean change. A conflict keeps
  its resolution UI instead, and an `:unsupported` entry is a refusal the
  merge raises whatever the selection says."
  [item]
  (contains? #{:added :modified :deleted} (:status item)))

(defn- review-key
  "The key a row's review mark is stored under."
  [item]
  (or (:key item) (dm/str (name (:kind item)) ":" (:id item))))

(defn- category-label
  [cat]
  (tr (brc/category->label cat)))

;; --- List rows

(defn- dom-stop
  [event]
  (.stopPropagation event))

(mf/defc row-end*
  {::mf/private true}
  [{:keys [item excluded reviewed]}]
  (let [status (:status item)
        edits  (count (brc/visible-attrs (:changed-attrs item)))]
    (cond
      ;; the warning icon alone: the bar and the group already say it
      ;; waits for a decision
      (= :conflict status)
      [:> i/icon* {:icon-id i/triangle-alert
                   :size "s"
                   :class (stl/css :row-warning)
                   :aria-label (tr "workspace.branches.review.row.choose")}]

      excluded
      [:> tag* {:variant "pill"} (tr "workspace.branches.review.row.left-out")]

      ;; reviewing is the user's progress, not what happened to the
      ;; entry: a quiet eye, seen by a person, never a coloured status
      reviewed
      [:> tooltip* {:content (tr "workspace.branches.review.row.reviewed")
                    :placement "top"}
       [:> i/icon* {:icon-id i/shown
                    :size "s"
                    :class (stl/css :row-reviewed)}]]

      (= :added status)
      [:> tag* {:tone "success" :variant "pill"} (tr "workspace.branches.status.added")]

      (= :deleted status)
      [:> tag* {:tone "error" :variant "pill"} (tr "workspace.branches.status.deleted")]

      (> edits 1)
      [:> tag* {:tone "info" :variant "pill"}
       (tr "workspace.branches.review.row.edits" (i18n/c edits))]

      :else nil)))

(mf/defc item-row*
  {::mf/private true}
  [{:keys [index item selected excluded reviewed on-select on-toggle]}]
  (let [selectable? (selectable-item? item)
        change-key  (:key item)
        on-click    (mf/use-fn (mf/deps index on-select) #(on-select {:kind :item :index index}))
        start       (when selectable?
                      (mf/html
                       [:span {:class (stl/css :row-check)
                               ;; the click must not also select the row
                               :on-click dom-stop}
                        [:> checkbox* {:id (dm/str "change-" change-key)
                                       :checked (not excluded)
                                       :on-change #(on-toggle #{change-key})}]]))]
    [:> change-row* {:title (or (:label item) "")
                     :subtitle (brc/change-summary item)
                     :icon (brc/entry-icon item)
                     :tone (if (and excluded selectable?) "neutral" (brc/status-tone (:status item)))
                     :is-selected (= selected {:kind :item :index index})
                     :is-muted (and excluded selectable?)
                     :start start
                     :end (mf/html [:> row-end* {:item item :excluded excluded :reviewed reviewed}])
                     :on-click on-click}]))

(mf/defc board-row*
  "A top-level board that holds changes; its changes fold under it."
  {::mf/private true}
  [{:keys [group board selected collapsed on-select on-fold children]}]
  (let [sel       {:kind :board :page-id (:page-id group) :board-id (:id board)}
        open?     (not (contains? collapsed (:id board)))
        on-click  (mf/use-fn (mf/deps sel on-select) #(on-select sel))
        on-chevron (mf/use-fn (mf/deps board on-fold)
                              (fn [event]
                                (.stopPropagation event)
                                (on-fold (:id board))))]
    [:*
     [:> change-row* {:title (or (:name board) "")
                      :subtitle (tr "workspace.branches.review.board.inside"
                                    (i18n/c (count (:rows board))))
                      ;; a fold is not a change: no status colour
                      :tone "neutral"
                      :is-selected (= selected sel)
                      :start (mf/html
                              [:button {:class (stl/css :row-fold)
                                        :aria-expanded open?
                                        :on-click on-chevron}
                               [:> i/icon* {:icon-id (if open? i/arrow-down i/arrow-right)
                                            :size "s"}]])
                      :on-click on-click}]
     (when open? children)]))

(mf/defc change-list*
  {::mf/private true}
  [{:keys [groups conflicts selected excluded reviewed collapsed
           on-select on-toggle on-fold]}]
  [:*
   (when (seq conflicts)
     (let [open? (not (contains? collapsed "decisions"))]
       [:div {:class (stl/css :group)}
        [:> brl/group-head* {:label (tr "workspace.branches.review.group.decisions"
                                        (str (count conflicts)))
                             :is-open open?
                             :on-toggle #(on-fold "decisions")}]
        (when open?
          (for [[idx item] conflicts]
            [:> item-row* {:key idx :index idx :item item
                           :selected selected
                           :reviewed (contains? reviewed (review-key item))
                           :on-select on-select :on-toggle on-toggle}]))]))

   (for [{:keys [id name rows boards count] :as group} groups]
     (let [open?     (not (contains? collapsed id))
           row-keys  (into #{} (comp (filter (fn [[_ it]] (selectable-item? it)))
                                     (map (fn [[_ it]] (:key it))))
                           (concat rows (mapcat :rows boards)))
           included? (not-any? excluded row-keys)]
       [:div {:class (stl/css :group) :key id}
        [:> brl/group-head*
         {:label (dm/str name " (" count ")")
          :is-open open?
          :on-toggle #(on-fold id)
          :start (when (seq row-keys)
                   (mf/html
                    [:span {:class (stl/css :row-check)}
                     [:> checkbox* {:id (dm/str "group-" id)
                                    :checked included?
                                    :on-change #(on-toggle row-keys (not included?))}]]))}]
        (when open?
          [:*
           (for [[idx item] rows]
             [:> item-row* {:key idx :index idx :item item
                            :selected selected
                            :excluded (contains? excluded (:key item))
                            :reviewed (contains? reviewed (review-key item))
                            :on-select on-select :on-toggle on-toggle}])
           (for [board boards]
             [:> board-row* {:key (str (:id board))
                             :group group :board board
                             :selected selected :collapsed collapsed
                             :on-select on-select :on-fold on-fold}
              (for [[idx item] (:rows board)]
                [:> item-row* {:key idx :index idx :item item
                               :selected selected
                               :excluded (contains? excluded (:key item))
                               :reviewed (contains? reviewed (review-key item))
                               :on-select on-select :on-toggle on-toggle}])])])]))])

;; --- Previews

(def ^:private view-modes
  [[:side    i/side-by-side         "workspace.branches.review.view.side"]
   [:overlay i/boolean-intersection "workspace.branches.review.view.overlay"]])

(defn- library-entity
  "A library entry (colour, typography) as the open file holds it."
  [data item]
  (case (:kind item)
    :color      (get-in data [:colors (:id item)])
    :typography (get-in data [:typographies (:id item)])
    nil))

(mf/defc side-cards*
  "MAIN and BRANCH, side by side, each with what it holds for the change."
  {::mf/private true}
  [{:keys [kind main-value branch-value main-objects branch-objects
           main-detail branch-detail main-missing branch-missing large]}]
  [:div {:class (stl/css :cards)}
   [:> preview-card* {:label (tr "workspace.branches.review.side.main")
                      :side "main"
                      :detail main-detail
                      :is-large (boolean large)}
    [:> brp/entity-preview* {:kind kind
                             :value main-value
                             :objects main-objects
                             :missing main-missing}]]
   [:> preview-card* {:label (tr "workspace.branches.review.side.branch")
                      :side "branch"
                      :detail branch-detail
                      :is-large (boolean large)}
    [:> brp/entity-preview* {:kind kind
                             :value branch-value
                             :objects branch-objects
                             :missing branch-missing}]]])

(mf/defc view-modes*
  "Side by side or overlay for the previews of a change, as icon toggles,
  then the toggle that gives the previews the whole dialog. `show-modes`
  leaves the view toggles out for a change with nothing to overlay."
  {::mf/private true}
  [{:keys [mode on-change show-modes expanded on-expand]}]
  [:div {:class (stl/css :view-modes)}
   (when show-modes
     (for [[m icon label] view-modes]
       [:> icon-button* {:key (name m)
                         :variant "ghost"
                         :class (stl/css :view-toggle)
                         :icon icon
                         :aria-label (tr label)
                         :aria-pressed (= m mode)
                         :on-click #(on-change m)}]))
   [:> icon-button* {:variant "ghost"
                     :icon (if expanded i/collapse i/expand)
                     :aria-label (tr (if expanded
                                       "workspace.branches.review.view.collapse"
                                       "workspace.branches.review.view.expand"))
                     :on-click on-expand}]])

(mf/defc item-visual*
  "The visual comparison of one change, in the view `mode`."
  {::mf/private true}
  [{:keys [item items open-side incoming data diff-meta mode large]}]
  (let [kind      (:kind item)
        page-id   (:page-id item)
        objects   (when (= :shape kind)
                    (get-in data [:pages-index page-id :objects]))
        page-items (mf/with-memo [items page-id]
                     (filterv #(= page-id (:page-id %)) items))
        main-objs  (mf/with-memo [objects page-items open-side]
                     (when objects (brp/side-objects objects page-items :main open-side)))
        branch-objs (mf/with-memo [objects page-items open-side]
                      (when objects (brp/side-objects objects page-items :branch open-side)))
        main-v    (if (= :shape kind)
                    (or (get main-objs (:id item)) (get item :main))
                    (brp/side-entity item :main open-side (library-entity data item)))
        branch-v  (if (= :shape kind)
                    (or (get branch-objs (:id item)) (get item :branch))
                    (brp/side-entity item :branch open-side (library-entity data item)))
        main-detail   (some-> (:main-at diff-meta) ct/inst ct/timeago)
        branch-detail (some-> (:branch-at diff-meta) ct/inst ct/timeago)]
    (when (or main-v branch-v)
      [:div {:class (stl/css :visual)}
       (if (= mode :overlay)
         [:> preview-card* {:label (tr "workspace.branches.review.view.overlay")
                            :side "base"
                            :is-large (boolean large)
                            :detail (tr "workspace.branches.review.overlay-detail")}
          [:div {:class (stl/css :overlay-stage)}
           (when main-v
             [:div {:class (stl/css :overlay-layer)}
              [:> brp/entity-preview* {:kind kind :value main-v :objects main-objs}]])
           (when branch-v
             [:div {:class (stl/css :overlay-layer :is-top)}
              [:> brp/entity-preview* {:kind kind :value branch-v :objects branch-objs}]])]]
         [:> side-cards* {:kind kind
                          :large large
                          :main-value main-v
                          :branch-value branch-v
                          :main-objects main-objs
                          :branch-objects branch-objs
                          :main-detail main-detail
                          :branch-detail branch-detail
                          :main-missing {:reason (brp/missing-reason item :main incoming)
                                         :side :main
                                         :open-side open-side}
                          :branch-missing {:reason (brp/missing-reason item :branch incoming)
                                           :side :branch
                                           :open-side open-side}}])])))

;; --- Detail panes

(mf/defc item-detail*
  {::mf/private true}
  [{:keys [item items indexed excluded excluded-keys reviewed selected open-side incoming
           data diff-meta on-resolve on-select on-toggle expanded on-expand]}]
  (let [type-lbl (brc/type-label-key item)
        cat      (brc/category item)
        attrs    (:changed-attrs item)
        visible  (brc/visible-attrs attrs)
        conflict? (= :conflict (:status item))
        mode*    (mf/use-state :side)
        mode     (deref mode*)]
    [:*
     [:div {:class (stl/css :detail-head)}
      [:div {:class (stl/css :detail-head-main)}
       [:> heading* {:level 3 :typography t/body-large :class (stl/css :detail-title)}
        (:label item)]
       (when type-lbl
         [:> tag* {} (tr type-lbl)])
       ;; a decision already shows as one (the warning row, the notice);
       ;; its category tag would only repeat it
       (when-not conflict?
         [:> tag* {:tone (brc/category->tone cat)
                   :icon (brc/category->icon cat)}
          (category-label cat)])
       (when (and excluded (selectable-item? item))
         [:> tag* {} (tr "workspace.branches.review.row.left-out")])]
      [:> view-modes* {:mode mode
                       :on-change #(reset! mode* %)
                       :show-modes (= :shape (:kind item))
                       :expanded expanded
                       :on-expand on-expand}]]

     [:> item-visual* {:item item
                       :items items
                       :open-side open-side
                       :incoming incoming
                       :data data
                       :diff-meta diff-meta
                       :mode mode
                       :large expanded}]

     (when conflict?
       [:> brl/notice* {:level :warning}
        (tr "workspace.branches.review.decision.body")])

     ;; the summary heads the property table; a decision puts its way in
     ;; on the same line
     [:div {:class (stl/css :detail-summary-row)}
      [:> text* {:as "p" :typography t/body-small :class (stl/css :detail-summary)}
       (brc/change-summary item)]
      (when (and conflict? on-resolve)
        [:> button* {:variant "secondary" :on-click on-resolve}
         (tr "workspace.branches.review.row.choose")])]

     (if (seq visible)
       [:> brl/value-steps* {:attrs visible}]
       (when (= :modified (:status item))
         [:> text* {:as "p" :typography t/body-small :class (stl/css :detail-summary)}
          (tr "workspace.branches.compare.no-props")]))

     ;; an entry without a before and after says what it is, where it
     ;; lands and what it affects instead
     (when-not conflict?
       (case (:kind item)
         :page
         (when (contains? #{:added :deleted} (:status item))
           (let [rows (filterv (fn [[_ it]] (= (:id item) (:page-id it))) indexed)]
             [:> brf/page-facts* {:item item
                                  :data data
                                  :open-side open-side
                                  :incoming incoming
                                  :change-count (count rows)}
              (for [[idx it] rows]
                [:> item-row* {:key idx :index idx :item it
                               :selected selected
                               :excluded (contains? excluded-keys (:key it))
                               :reviewed (contains? reviewed (review-key it))
                               :on-select on-select :on-toggle on-toggle}])]))

         :shape
         (when (contains? #{:added :deleted} (:status item))
           [:> brf/shape-facts* {:item item :data data :incoming incoming}])

         (:component :color :typography :token)
         [:> brf/usage-facts* {:item item :data data}]

         nil))]))

(mf/defc board-detail*
  {::mf/private true}
  [{:keys [page-id board-id groups items open-side data diff-meta selected excluded reviewed
           on-select on-toggle]}]
  (let [group    (some #(when (= page-id (:page-id %)) %) groups)
        board    (some #(when (= board-id (:id %)) %) (:boards group))
        rows     (:rows board)
        objects  (get-in data [:pages-index page-id :objects])
        page-items (mf/with-memo [items page-id]
                     (filterv #(= page-id (:page-id %)) items))
        main-objs (mf/with-memo [objects page-items open-side]
                    (when objects (brp/side-objects objects page-items :main open-side)))
        branch-objs (mf/with-memo [objects page-items open-side]
                      (when objects (brp/side-objects objects page-items :branch open-side)))]
    (when board
      [:*
       [:div {:class (stl/css :detail-head)}
        [:> heading* {:level 3 :typography t/body-large :class (stl/css :detail-title)}
         (:name board)]
        [:> tag* {} (tr "workspace.branches.compare.shape.board")]]
       [:> text* {:as "p" :typography t/body-small :class (stl/css :detail-summary)}
        (dm/str (:name group) " › " (:name board) " - "
                (tr "workspace.branches.review.board.inside" (i18n/c (count rows))))]
       [:div {:class (stl/css :cards)}
        [:> preview-card* {:label (tr "workspace.branches.review.side.main")
                           :side "main"
                           :detail (some-> (:main-at diff-meta) ct/inst ct/timeago)}
         [:> brp/entity-preview* {:kind :shape
                                  :value (get main-objs board-id)
                                  :objects main-objs
                                  :missing {:side :main :open-side open-side}}]]
        [:> preview-card* {:label (tr "workspace.branches.review.side.branch")
                           :side "branch"
                           :detail (some-> (:branch-at diff-meta) ct/inst ct/timeago)}
         [:> brp/entity-preview* {:kind :shape
                                  :value (get branch-objs board-id)
                                  :objects branch-objs
                                  :missing {:side :branch :open-side open-side}}]]]
       [:div {:class (stl/css :inner-list)}
        (for [[idx item] rows]
          [:> item-row* {:key idx :index idx :item item
                         :selected selected
                         :excluded (contains? excluded (:key item))
                         :reviewed (contains? reviewed (review-key item))
                         :on-select on-select :on-toggle on-toggle}])]])))

;; --- The dialog

(defn- display-order
  "Every row index in the order the list shows them: decisions first,
  then each page's loose rows and its boards' rows."
  [conflicts groups]
  (-> []
      (into (map first) conflicts)
      (into (mapcat (fn [{:keys [rows boards]}]
                      (concat (map first rows)
                              (mapcat (comp (partial map first) :rows) boards))))
            groups)))

(mf/defc branch-compare-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-compare}
  [{:keys [branch is-review]}]
  (let [{:keys [status diff direction excluded reviewed]} (mf/deref branch-diff)
        file-id    (:id (mf/deref refs/file))
        data       (mf/deref refs/workspace-data)
        direction  (or direction :branch->main)
        incoming?  (= direction :main->branch)
        excluded   (or excluded #{})
        reviewed   (or reviewed #{})
        open-side  (brp/open-side branch file-id)
        diff-meta  (:meta diff)

        items      (mf/with-memo [diff]
                     (into (vec (:changes diff)) (:conflicts diff)))
        indexed    (mf/with-memo [items]
                     (into [] (map-indexed vector) items))
        counts     (mf/with-memo [items]
                     (group-by brc/category items))

        filter*    (mf/use-state :all)
        active-filter (deref filter*)
        selected*  (mf/use-state nil)
        selected   (deref selected*)
        ;; expanded: the list steps aside and the previews take the
        ;; whole dialog
        expanded*  (mf/use-state false)
        expanded   (deref expanded*)
        on-expand  (mf/use-fn #(swap! expanded* not))
        collapsed* (mf/use-state #{})
        collapsed  (deref collapsed*)

        visible    (mf/with-memo [indexed active-filter excluded]
                     (filterv (fn [[_ item]]
                                (case active-filter
                                  :all      true
                                  :left-out (contains? excluded (:key item))
                                  (= active-filter (brc/category item))))
                              indexed))
        conflicts  (mf/with-memo [visible]
                     (filterv (fn [[_ it]] (= :conflict (:status it))) visible))
        decisions  (count (filter #(= :conflict (:status %)) items))
        groups     (mf/with-memo [visible data]
                     (brc/group-by-place (filterv (fn [[_ it]] (not= :conflict (:status it))) visible)
                                         (:pages-index data)
                                         (:pages data)))
        order      (mf/with-memo [conflicts groups]
                     (display-order conflicts groups))

        all-keys   (into #{} (comp (filter selectable-item?) (map :key)) items)
        kept-count (count (remove excluded all-keys))
        left-out   (count (filter excluded all-keys))
        reviewed-n (count (filter #(contains? reviewed (review-key %)) items))

        sel-index  (when (= :item (:kind selected)) (:index selected))
        sel-item   (when (and (some? sel-index) (< sel-index (count items)))
                     (nth items sel-index))
        pos        (when sel-index (.indexOf order sel-index))

        on-close   (mf/use-fn #(st/emit! (dwb/clear-diff-excluded) (modal/hide)))
        on-select  (mf/use-fn #(reset! selected* %))
        on-toggle  (mf/use-fn (fn [change-keys include?]
                                (st/emit! (dwb/toggle-diff-excluded change-keys include?))))
        on-fold    (mf/use-fn (fn [id] (swap! collapsed* #(if (contains? % id) (disj % id) (conj % id)))))
        on-filter  (mf/use-fn (fn [f]
                                (reset! filter* f)
                                (reset! selected* nil)))
        on-swap    (mf/use-fn (mf/deps branch direction)
                              #(st/emit! (dwb/fetch-branch-diff
                                          (:id branch)
                                          (if incoming? :branch->main :main->branch))))
        on-merge   (mf/use-fn (mf/deps branch excluded)
                              #(brc/confirm-merge! branch excluded))
        on-update  (mf/use-fn (mf/deps branch excluded)
                              #(brc/confirm-update! branch excluded))
        on-resolve (mf/use-fn (mf/deps branch direction excluded)
                              #(modal/show! :branch-conflicts
                                            {:branch branch
                                             :mode (if incoming? :update :merge)
                                             :excluded excluded}))
        on-previous (mf/use-fn (mf/deps order pos)
                               (fn []
                                 (when (and pos (pos? pos))
                                   (reset! selected* {:kind :item :index (nth order (dec pos))}))))
        on-mark-next
        (mf/use-fn
         (mf/deps order pos sel-item items reviewed)
         (fn []
           (when sel-item
             (st/emit! (dwb/toggle-diff-reviewed (review-key sel-item) true)))
           (let [start   (if pos (inc pos) 0)
                 ahead   (concat (drop start order) (take start order))
                 next-ix (some (fn [idx]
                                 (when (and (not= idx (:index selected))
                                            (not (contains? reviewed (review-key (nth items idx)))))
                                   idx))
                               ahead)]
             (when next-ix
               (reset! selected* {:kind :item :index next-ix})))))
        on-export  (mf/use-fn
                    (mf/deps diff branch)
                    (fn []
                      (let [payload (clj->js {:stats (:stats diff)
                                              :changes (:changes diff)
                                              :conflicts (:conflicts diff)})
                            text    (js/JSON.stringify payload nil 2)
                            blob    (js/Blob. #js [text] #js {:type "application/json"})
                            url     (js/URL.createObjectURL blob)
                            a       (.createElement js/document "a")]
                        (set! (.-href a) url)
                        (set! (.-download a) (dm/str (:name branch) "-diff.json"))
                        (.click a)
                        (js/URL.revokeObjectURL url))))

        primary
        (mf/html
         (cond
           (pos? decisions)
           [:> button* {:variant "primary" :on-click on-resolve}
            (tr "workspace.branches.review.action.resolve" (i18n/c decisions))]

           incoming?
           [:> button* {:variant "primary"
                        :disabled (zero? kept-count)
                        :on-click on-update}
            (tr "workspace.branches.review.action.update" (i18n/c kept-count))]

           :else
           [:> button* {:variant "primary"
                        :disabled (zero? kept-count)
                        :on-click on-merge}
            (tr "workspace.branches.review.action.merge" (i18n/c kept-count))]))]

    (mf/with-effect [(:id branch)]
      (st/emit! (dwb/fetch-branch-diff (:id branch))))

    ;; the list opens on its first row
    (mf/with-effect [status order]
      (when (and (= status :loaded) (nil? selected) (seq order))
        (reset! selected* {:kind :item :index (first order)})))

    [:> brl/review-modal*
     {:icon i/git-compare-arrows
      :title (tr "workspace.branches.review.title")
      :route (mf/html [:> brl/route* {:branch-name (:name branch)
                                      :is-review is-review
                                      :incoming incoming?
                                      :on-swap on-swap}])
      :filters (when (and (= status :loaded) (seq items) (not expanded))
                 (mf/html
                  [:*
                   [:> filter-chip* {:label (dm/str (tr "labels.all") " (" (count items) ")")
                                     :is-selected (= active-filter :all)
                                     :on-click #(on-filter :all)}]
                   (for [[cat tone label icon] brc/categories
                         :let [n (count (get counts cat))]
                         :when (pos? n)]
                     [:> filter-chip* {:key (name cat)
                                       :label (dm/str (tr label) " (" n ")")
                                       :icon icon
                                       :tone tone
                                       :is-selected (= active-filter cat)
                                       :on-click #(on-filter cat)}])
                   (when (pos? left-out)
                     [:> filter-chip* {:label (dm/str (tr "workspace.branches.review.row.left-out")
                                                      " (" left-out ")")
                                       :is-selected (= active-filter :left-out)
                                       :on-click #(on-filter :left-out)}])]))
      :list (when (and (= status :loaded) (seq items) (not expanded))
              (mf/html
               [:> change-list* {:groups groups
                                 :conflicts conflicts
                                 :selected selected
                                 :excluded excluded
                                 :reviewed reviewed
                                 :collapsed collapsed
                                 :on-select on-select
                                 :on-toggle on-toggle
                                 :on-fold on-fold}]))
      :detail
      (mf/html
       (cond
         (= status :error)
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/triangle-alert :text (tr "workspace.branches.compare.error")}]]

         (not= status :loaded)
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/git-compare-arrows :text (tr "workspace.branches.compare.loading")}]]

         (empty? items)
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/git-branch :text (tr "workspace.branches.compare.empty")}]]

         (= :board (:kind selected))
         [:> board-detail* {:page-id (:page-id selected)
                            :board-id (:board-id selected)
                            :groups groups
                            :items items
                            :open-side open-side
                            :data data
                            :diff-meta diff-meta
                            :selected selected
                            :excluded excluded
                            :reviewed reviewed
                            :on-select on-select
                            :on-toggle on-toggle}]

         sel-item
         [:> item-detail* {:key (:index selected)
                           :item sel-item
                           :items items
                           :indexed indexed
                           :excluded (contains? excluded (:key sel-item))
                           :excluded-keys excluded
                           :reviewed reviewed
                           :selected selected
                           :on-select on-select
                           :on-toggle on-toggle
                           :expanded expanded
                           :on-expand on-expand
                           :open-side open-side
                           :incoming incoming?
                           :data data
                           :diff-meta diff-meta
                           :on-resolve on-resolve}]

         :else
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/git-compare-arrows :text (tr "workspace.branches.compare.select-hint")}]]))

      :footer-start
      (when (= status :loaded)
        (mf/html
         [:div {:class (stl/css :footer-start)}
          [:> icon-button* {:variant "ghost"
                            :icon i/download
                            :aria-label (tr "workspace.branches.compare.export")
                            :on-click on-export}]
          [:> text* {:as "span" :typography t/body-small :class (stl/css :footer-status)}
           (str/join " - "
                     (remove nil?
                             [(tr "workspace.branches.review.footer.reviewed"
                                  (str reviewed-n) (str (count items)))
                              (when (pos? decisions)
                                (tr "workspace.branches.review.footer.blocked" (i18n/c decisions)))
                              (when (pos? left-out)
                                (tr "workspace.branches.review.footer.left-out" (i18n/c left-out)))]))]]))
      :footer-end
      (when (and (= status :loaded) (seq items))
        (mf/html
         [:*
          [:> button* {:variant "secondary"
                       :disabled (not (and pos (pos? pos)))
                       :on-click on-previous}
           (tr "workspace.branches.review.action.previous")]
          [:> button* {:variant "secondary"
                       :disabled (nil? sel-item)
                       :on-click on-mark-next}
           (tr "workspace.branches.review.action.mark-next")]
          primary]))
      :on-close on-close}]))
