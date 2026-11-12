;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-review.conflicts
  "The decisions dialog: one entity both sides changed at a time. Only the
  properties really in conflict ask for a choice; the ones a single side
  changed are filled in from that side, and the dialog says what each
  choice drops before the merge applies it."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.change-row :refer [change-row*]]
   [app.main.ui.ds.product.empty-state :refer [empty-state*]]
   [app.main.ui.ds.product.filter-chip :refer [filter-chip*]]
   [app.main.ui.ds.product.preview-card :refer [preview-card*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.facts :as brf]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private branch-diff
  (l/derived :workspace-branch-diff st/state))

(defn- reason-label
  "Why both sides clash on an entity, or nil for any other reason."
  [reason]
  (case reason
    :modify-modify (tr "workspace.branches.conflicts.reason.modify-modify")
    :add-add       (tr "workspace.branches.conflicts.reason.add-add")
    :delete-modify (tr "workspace.branches.conflicts.reason.delete-modify")
    :modify-delete (tr "workspace.branches.conflicts.reason.modify-delete")
    nil))

(defn- text-conflict?
  "A conflict whose text content both sides rewrote."
  [conflict]
  (and (brc/per-attr? conflict)
       (some #(and (= :content (:attr %)) (= :conflict (:source %)))
             (brc/conflict-attr-rows conflict))))

(defn- loses-work?
  "Whether every choice on a text conflict drops a paragraph edit: both
  sides edited different paragraphs."
  [conflict]
  (and (text-conflict? conflict)
       (some #(= :dropped (:status %)) (brc/paragraph-rows conflict :main))
       (some #(= :dropped (:status %)) (brc/paragraph-rows conflict :branch))))

(defn- row-subtitle
  [conflict res]
  (let [rows        (brc/conflict-attr-rows conflict)
        conflicting (brc/conflicting-attrs rows)
        drops       (brc/dropped-values conflict res)]
    (cond
      (bm/conflict-resolved? conflict res)
      (cond
        (= res :main)   (tr "workspace.branches.resolve.row.kept-main")
        (= res :branch) (tr "workspace.branches.resolve.row.kept-branch")
        (seq drops)     (tr "workspace.branches.resolve.row.drops"
                            (str/lower (str/join ", " (map (comp brc/attr-label :attr) drops))))
        :else           (tr "workspace.branches.resolve.row.kept-both"))

      (seq conflicting)
      (tr "workspace.branches.resolve.row.both-changed"
          (str/join ", " (map brc/attr-label conflicting)))

      :else
      (reason-label (:reason conflict)))))

(mf/defc conflict-row*
  {::mf/private true}
  [{:keys [conflict index selected res on-select]}]
  (let [resolved? (bm/conflict-resolved? conflict res)
        on-click  (mf/use-fn (mf/deps index on-select) #(on-select index))]
    [:> change-row* {:title (or (:label conflict) "")
                     :subtitle (row-subtitle conflict res)
                     :icon (brc/entry-icon conflict)
                     :tone (if resolved? "success" "warning")
                     :is-selected (= index selected)
                     :end (mf/html
                           (cond
                             resolved?
                             [:> tag* {:tone "success" :variant "pill"}
                              (tr "workspace.branches.resolve.decided")]
                             (loses-work? conflict)
                             [:> tag* {:tone "error" :variant "pill"}
                              (tr "workspace.branches.resolve.row.loses-work")]
                             :else
                             [:> i/icon* {:icon-id i/triangle-alert
                                          :size "s"
                                          :class (stl/css :row-warning)
                                          :aria-label (tr "workspace.branches.review.row.choose")}]))
                     :on-click on-click}]))

;; --- Property table

(mf/defc pick-button*
  "The value of one side in the property table: a click keeps it."
  {::mf/private true}
  [{:keys [attr side value choice is-conflicting on-pick]}]
  (let [on-click (mf/use-fn (mf/deps attr side on-pick) #(on-pick attr side))]
    [:button {:class (stl/css-case :td-pick true :is-chosen (and is-conflicting (= choice side)))
              :disabled (not is-conflicting)
              :on-click on-click}
     [:> brl/value-chip* {:value value :is-old (not= choice side)}]]))

(mf/defc attr-table*
  "Property | Main | Branch | Result, with the source of each result."
  {::mf/private true}
  [{:keys [rows res on-pick]}]
  [:div {:class (stl/css :table)}
   [:div {:class (stl/css :table-row :is-head)}
    (for [label [(tr "workspace.branches.resolve.th.property")
                 (tr "workspace.branches.resolve.th.main")
                 (tr "workspace.branches.resolve.th.branch")
                 (tr "workspace.branches.resolve.th.result")]]
      [:> text* {:key label :as "span" :typography t/headline-small :class (stl/css :th)} label])
    [:span]]
   (for [{:keys [attr main branch source]} rows
         :let [choice (brc/attr-choice res attr)
               conflicting? (= source :conflict)
               result (case (or choice (when-not conflicting? source))
                        :main   main
                        :branch branch
                        nil)
               decided? (some? (or choice (when-not conflicting? source)))]]
     [:div {:class (stl/css-case :table-row true :is-conflicting (and conflicting? (nil? choice)))
            :key (str attr)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :td-name)}
       (brc/attr-label attr)]
      (if (= source :branch)
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-unchanged)}
         (tr "workspace.branches.resolve.unchanged")]
        [:> pick-button* {:attr attr
                          :side :main
                          :value main
                          :choice choice
                          :is-conflicting conflicting?
                          :on-pick on-pick}])
      (if (= source :main)
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-unchanged)}
         (tr "workspace.branches.resolve.unchanged")]
        [:> pick-button* {:attr attr
                          :side :branch
                          :value branch
                          :choice choice
                          :is-conflicting conflicting?
                          :on-pick on-pick}])
      (if decided?
        [:span {:class (stl/css :td-result)}
         [:> brl/value-chip* {:value result}]]
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-choose)}
         (tr "workspace.branches.resolve.choose-side")])
      [:span {:class (stl/css :td-tag)}
       (cond
         ;; a decided conflict is done (green); an independent edit takes the
         ;; colour of the side it comes from, as the preview cards do
         (and conflicting? choice)
         [:> tag* {:tone "success"} (tr "workspace.branches.resolve.tag.your-choice")]
         conflicting?
         [:> tag* {:tone "warning"} (tr "workspace.branches.resolve.tag.conflict")]
         (= source :main)
         [:> tag* {:tone "purple"} (tr "workspace.branches.resolve.tag.from-main")]
         :else
         [:> tag* {:tone "accent"} (tr "workspace.branches.resolve.tag.from-branch")])]])])

;; --- Whole-version and text choices

(mf/defc option-card*
  "A choice between the two versions, as a radio card."
  {::mf/private true}
  [{:keys [label side detail is-chosen warning on-choose children]}]
  (let [on-click (mf/use-fn (mf/deps side on-choose) #(on-choose side))]
    [:button {:class (stl/css-case :option true :is-chosen is-chosen)
              :role "radio"
              :aria-checked (boolean is-chosen)
              :on-click on-click}
     [:div {:class (stl/css :option-head)}
      [:span {:class (stl/css-case :option-radio true :is-chosen is-chosen) :aria-hidden true}]
      [:> text* {:as "span" :typography t/body-small :class (stl/css :option-label)} label]]
     [:div {:class (stl/css :option-side)}
      [:> text* {:as "span" :typography t/headline-small
                 :class (stl/css-case :option-side-label true :is-main (= side :main))}
       (if (= side :main)
         (tr "workspace.branches.review.side.main")
         (tr "workspace.branches.review.side.branch"))]
      (when detail
        [:> text* {:as "span" :typography t/body-small :class (stl/css :option-detail)} detail])]
     children
     (when warning
       [:span {:class (stl/css :option-warning)}
        [:> i/icon* {:icon-id i/triangle-alert :size "s"}]
        warning])]))

(mf/defc paragraph-list*
  {::mf/private true}
  [{:keys [rows]}]
  [:div {:class (stl/css :paragraphs)}
   (for [{:keys [index text status]} rows]
     [:> text* {:key index
                :as "span"
                :typography t/body-small
                :class (stl/css-case :paragraph true
                                     :is-kept (= status :kept)
                                     :is-dropped (= status :dropped))}
      (dm/str "¶" (inc index) " " text)])])

(defn- dropped-paragraphs
  "The paragraph edits keeping `side` drops."
  [conflict side]
  (let [other (if (= side :main) :branch :main)]
    (filterv #(= :kept (:status %)) (brc/paragraph-rows conflict other))))

(mf/defc text-choice*
  {::mf/private true}
  [{:keys [conflict res main-detail branch-detail on-pick]}]
  (let [choice  (brc/attr-choice res :content)
        on-choose (mf/use-fn (mf/deps on-pick) #(on-pick :content %))
        on-copy (mf/use-fn
                 (mf/deps conflict choice)
                 (fn []
                   (when choice
                     (let [lost (dropped-paragraphs conflict choice)]
                       (-> (clipboard/to-clipboard (str/join "\n" (map :text lost)))
                           (.catch (constantly nil)))))))]
    [:*
     [:div {:class (stl/css :options)}
      (for [side [:main :branch]
            :let [lost (dropped-paragraphs conflict side)]]
        [:> option-card* {:key (name side)
                          :label (if (= side :main)
                                   (tr "workspace.branches.resolve.keep-main-text")
                                   (tr "workspace.branches.resolve.keep-branch-text"))
                          :side side
                          :detail (if (= side :main) main-detail branch-detail)
                          :is-chosen (= choice side)
                          :warning (when (seq lost)
                                     (tr "workspace.branches.resolve.drops-paragraphs"
                                         (str/join ", " (map #(dm/str "¶" (inc (:index %))) lost))))
                          :on-choose on-choose}
         [:> paragraph-list* {:rows (brc/paragraph-rows conflict side)}]])]
     (when (and choice (seq (dropped-paragraphs conflict choice)))
       [:div {:class (stl/css :inline-actions)}
        [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
         (tr "workspace.branches.resolve.keep-safe")]
        [:> button* {:variant "secondary" :on-click on-copy}
         (tr "workspace.branches.resolve.copy-dropped")]])]))

(mf/defc whole-choice*
  "The choice for a conflict that cannot be split by property: one side
  deleted the entity, or both added it differently."
  {::mf/private true}
  [{:keys [conflict res main-detail branch-detail on-choose]}]
  [:div {:class (stl/css :options)}
   (for [side [:main :branch]
         :let [value (get conflict side)]]
     [:> option-card* {:key (name side)
                       :label (if (= side :main)
                                (tr "workspace.branches.resolve.keep-main-version")
                                (tr "workspace.branches.resolve.keep-branch-version"))
                       :side side
                       :detail (if (= side :main) main-detail branch-detail)
                       :is-chosen (= res side)
                       :warning (when (nil? value)
                                  (tr "workspace.branches.resolve.deletes-it"))
                       :on-choose on-choose}
      [:div {:class (stl/css :option-stage)}
       [:> brp/entity-preview* {:kind (:kind conflict)
                                :value value
                                :missing {:reason :deleted :side side}}]]])])

;; --- Detail

(mf/defc attr-actions*
  "The two sides of a property in conflict, as buttons that keep one."
  {::mf/private true}
  [{:keys [attr main branch res on-pick]}]
  (let [choice    (brc/attr-choice res attr)
        on-main   (mf/use-fn (mf/deps attr on-pick) #(on-pick attr :main))
        on-branch (mf/use-fn (mf/deps attr on-pick) #(on-pick attr :branch))]
    [:div {:class (stl/css :inline-actions)}
     [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
      (dm/str (brc/attr-label attr) ":")]
     [:> button* {:variant (if (= choice :main) "primary" "secondary")
                  :on-click on-main}
      (tr "workspace.branches.resolve.keep-main" (brc/display-val main))]
     [:> button* {:variant (if (= choice :branch) "primary" "secondary")
                  :on-click on-branch}
      (tr "workspace.branches.resolve.keep-branch" (brc/display-val branch))]]))

(mf/defc conflict-detail*
  {::mf/private true}
  [{:keys [conflict res diff-meta copies data on-pick on-choose on-clear]}]
  (let [rows       (brc/conflict-attr-rows conflict)
        copies-of  (mf/with-memo [copies conflict data]
                     (brc/copies-entry copies conflict
                                       (get-in data [:pages-index (:page-id conflict) :objects])))
        per-attr?  (brc/per-attr? conflict)
        text?      (text-conflict? conflict)
        table-rows (if text? (filterv #(not= :content (:attr %)) rows) rows)
        conflict-n (count (brc/conflicting-attrs rows))
        indep-n    (- (count rows) conflict-n)
        resolved?  (bm/conflict-resolved? conflict res)
        drops      (when resolved? (brc/dropped-values conflict res))
        main-detail   (some-> (:main-at diff-meta) ct/inst ct/timeago)
        branch-detail (some-> (:branch-at diff-meta) ct/inst ct/timeago)
        prefill    (brc/prefill-resolution conflict)
        result     (when per-attr?
                     (reduce (fn [entity attr]
                               (let [side (or (brc/attr-choice res attr)
                                              (get prefill attr)
                                              :main)
                                     v    (get-in conflict [side attr])]
                                 (if (nil? v) (dissoc entity attr) (assoc entity attr v))))
                             (:main conflict)
                             (keys (:changed-attrs conflict))))]
    [:*
     [:div {:class (stl/css :detail-head)}
      [:> heading* {:level 3 :typography t/body-large :class (stl/css :detail-title)}
       (:label conflict)]
      (if resolved?
        [:> tag* {:tone "success"} (tr "workspace.branches.resolve.decided")]
        [:> tag* {:tone "warning"}
         (if per-attr?
           (dm/str (tr "workspace.branches.resolve.tag.conflicts" (i18n/c conflict-n))
                   (when (pos? indep-n)
                     (dm/str " - " (tr "workspace.branches.resolve.tag.independent" (i18n/c indep-n)))))
           (or (reason-label (:reason conflict))
               (tr "workspace.branches.status.conflict")))])]

     ;; what each side looks like comes first: the previews, or the two
     ;; versions to pick from when the entity cannot be split by property
     (cond
       (not per-attr?)
       [:> whole-choice* {:conflict conflict :res res
                          :main-detail main-detail :branch-detail branch-detail
                          :on-choose on-choose}]

       text?
       [:> text-choice* {:conflict conflict :res res
                         :main-detail main-detail :branch-detail branch-detail
                         :on-pick on-pick}]

       :else
       [:div {:class (stl/css :cards)}
        [:> preview-card* {:label (tr "workspace.branches.review.side.main")
                           :side "main" :detail main-detail}
         [:> brp/entity-preview* {:kind (:kind conflict)
                                  :value (:main conflict)
                                  :missing {:reason :deleted :side :main}}]]
        [:> preview-card* {:label (tr "workspace.branches.review.side.branch")
                           :side "branch" :detail branch-detail}
         [:> brp/entity-preview* {:kind (:kind conflict)
                                  :value (:branch conflict)
                                  :missing {:reason :deleted :side :branch}}]]
        [:> preview-card* {:label (tr "workspace.branches.review.side.result")
                           :side "result"}
         [:> brp/entity-preview* {:kind (:kind conflict) :value result}]]])

     (when (and per-attr? (seq table-rows))
       [:> attr-table* {:rows table-rows :res res :on-pick on-pick}])

     (when per-attr?
       (for [{:keys [attr main branch source]} rows
             :when (and (= :conflict source) (not= attr :content))]
         [:> attr-actions* {:key (str attr)
                            :attr attr
                            :main main
                            :branch branch
                            :res res
                            :on-pick on-pick}]))

     ;; the copy rule: what it decided on this copy, how many copies a
     ;; choice on a main decides, and which copies follow the component
     (when (:settled conflict)
       [:> brf/settled-facts* {:settled (:settled conflict) :is-conflict true}])
     (when (:copies-affected conflict)
       [:> brf/copies-affected* {:n (:copies-affected conflict)}])
     (when copies-of
       [:> brf/copies-facts* {:entry copies-of :copies copies :data data}])

     ;; once decided, one quiet block says what the merge leaves behind,
     ;; with the way back; until then, a note says how the choice works
     (if resolved?
       [:div {:class (stl/css :outcome)}
        [:div {:class (stl/css :outcome-text)}
         [:> text* {:as "span" :typography t/body-small :class (stl/css :outcome-title)}
          (tr "workspace.branches.resolve.drops.title")]
         (if (seq drops)
           (for [{:keys [attr side value]} drops]
             [:> text* {:key (str attr)
                        :as "span"
                        :typography t/body-small
                        :class (stl/css :outcome-line)}
              (if (= side :main)
                (tr "workspace.branches.resolve.drops.main-short" (str/lower (brc/attr-label attr)))
                (tr "workspace.branches.resolve.drops.branch-short" (str/lower (brc/attr-label attr))))
              " "
              [:span {:class (stl/css :outcome-dropped)} (brc/display-val value)]])
           [:> text* {:as "span" :typography t/body-small :class (stl/css :outcome-line)}
            (tr "workspace.branches.resolve.drops.none")])]
        [:> button* {:variant "ghost" :on-click on-clear}
         (tr "workspace.branches.resolve.change-choice")]]

       [:div {:class (stl/css :footnote)}
        [:> i/icon* {:icon-id i/info :size "s" :class (stl/css :footnote-icon)}]
        [:> text* {:as "p" :typography t/body-small :class (stl/css :footnote-text)}
         (cond
           text?     (tr "workspace.branches.resolve.intro-text")
           per-attr? (tr "workspace.branches.resolve.intro-attrs")
           :else     (tr "workspace.branches.resolve.intro-whole"))]])]))

;; --- The dialog

(mf/defc branch-conflicts-dialog*
  {::mf/register modal/components
   ::mf/register-as :branch-conflicts}
  [{:keys [branch mode conflicts excluded comment]}]
  (let [{:keys [diff resolutions status]} (mf/deref branch-diff)
        data        (mf/deref refs/workspace-data)
        copies      (:copies diff)

        loading?    (= status :loading)
        selected*   (mf/use-state 0)
        selected    (deref selected*)

        diff-meta   (:meta diff)

        ;; the command that opened this modal returned its own conflict set,
        ;; computed in the id space it resolves against; the diff this dialog
        ;; can fetch may have been computed in another id space and disagree,
        ;; which left the dialog showing 0 of 0 with an Apply that could never
        ;; enable. Prefer what the command said.
        conflicts   (if (seq conflicts) conflicts (:conflicts diff))

        ;; the payload's two sides are document-keyed in both directions:
        ;; `:main` holds main's value and `:branch` the branch's
        ;; (`branch-merge.cljc::compute-merge`), which is what every heading,
        ;; chip and button below names. Resolutions use the same vocabulary.
        resolutions (or resolutions {})
        total       (count conflicts)
        resolved?   (fn [c] (bm/conflict-resolved? c (get resolutions (:id c))))
        resolved    (count (filterv resolved? conflicts))
        pending     (- total resolved)
        all-done?   (and (pos? total) (zero? pending))

        sel-idx     (min selected (max 0 (dec total)))
        sel         (when (seq conflicts) (nth conflicts sel-idx))
        sel-res     (when sel (get resolutions (:id sel)))

        on-close    (mf/use-fn #(st/emit! (modal/hide)))
        on-back     (mf/use-fn (mf/deps branch)
                               #(modal/show! :branch-compare {:branch branch}))
        on-select   (mf/use-fn #(reset! selected* %))
        on-pick     (mf/use-fn
                     (mf/deps sel sel-res)
                     (fn [attr side]
                       (when sel
                         (st/emit! (dwb/set-conflict-resolutions
                                    {(:id sel) (brc/choose-attr sel sel-res attr side)})))))
        on-choose   (mf/use-fn
                     (mf/deps sel)
                     (fn [side]
                       (when sel
                         (st/emit! (dwb/set-conflict-resolution (:id sel) side)))))
        on-clear    (mf/use-fn
                     (mf/deps sel)
                     (fn []
                       (when sel
                         (st/emit! (dwb/clear-conflict-resolution (:id sel))
                                   (dwb/set-conflict-resolutions
                                    (if (brc/per-attr? sel)
                                      {(:id sel) (brc/prefill-resolution sel)}
                                      {}))))))
        on-all      (mf/use-fn
                     (mf/deps conflicts)
                     (fn [side]
                       (st/emit! (dwb/set-conflict-resolutions
                                  (into {} (map (fn [c] [(:id c) (brc/choose-side c side)]))
                                        conflicts)))))
        on-all-main   (mf/use-fn (mf/deps on-all) #(on-all :main))
        on-all-branch (mf/use-fn (mf/deps on-all) #(on-all :branch))
        indexed     (mf/with-memo [conflicts]
                      (into [] d/xf:add-index conflicts))
        on-next     (mf/use-fn
                     (mf/deps conflicts sel-idx resolutions)
                     (fn []
                       (let [n     (count conflicts)
                             order (map #(mod (+ sel-idx 1 %) n) (range n))
                             next-ix (some #(when-not (bm/conflict-resolved?
                                                       (nth conflicts %)
                                                       (get resolutions (:id (nth conflicts %))))
                                              %)
                                           order)]
                         (when next-ix (reset! selected* next-ix)))))
        on-apply    (mf/use-fn
                     ;; a merge sends again the comment of the merge dialog
                     (mf/deps branch resolutions mode excluded comment conflicts)
                     (fn []
                       (let [ids (into #{} (map :id) conflicts)
                             res (into {} (filter (fn [[id _]] (contains? ids id))) resolutions)]
                         (st/emit! (if (= mode :update)
                                     (dwb/update-branch-from-main branch {:resolutions res
                                                                          :excluded excluded})
                                     (dwb/merge-branch branch {:resolutions res
                                                               :comment comment
                                                               :excluded excluded}))))))]

    ;; the fetched diff backs both the fallback conflict set and the side
    ;; subtitles, so it must be computed in the direction this modal resolves
    (mf/with-effect [(:id branch) mode]
      (st/emit! (dwb/fetch-branch-diff (:id branch)
                                       (if (= mode :update) :main->branch :branch->main))))

    ;; independent edits are not in doubt: fill them in from the side that
    ;; made them, so only the conflicting properties wait for a choice
    (mf/with-effect [conflicts status]
      (when (and (seq conflicts) (not loading?))
        (let [seed (into {}
                         (keep (fn [c]
                                 (when (and (brc/per-attr? c)
                                            (nil? (get resolutions (:id c))))
                                   [(:id c) (brc/prefill-resolution c)])))
                         conflicts)]
          (when (seq seed)
            (st/emit! (dwb/set-conflict-resolutions seed))))))

    [:> brl/review-modal*
     {:icon i/triangle-alert
      :tone :warning
      :title (tr "workspace.branches.resolve.title")
      :route (mf/html [:> brl/route* {:branch-name (:name branch)
                                      :incoming (= mode :update)}])
      :filters (mf/html
                [:> filter-chip* {:icon i/triangle-alert
                                  :label (dm/str (tr "workspace.branches.review.category.decisions")
                                                 " (" total ")")
                                  :tone "warning"
                                  :is-selected true}])
      :list (when (and (not loading?) (seq conflicts))
              (mf/html
               [:*
                ;; deciding them all at once only means something with
                ;; more than one decision
                (when (> total 1)
                  [:div {:class (stl/css :bulk)}
                   [:> button* {:variant "secondary" :on-click on-all-main}
                    (tr "workspace.branches.resolve.keep-all-main")]
                   [:> button* {:variant "secondary" :on-click on-all-branch}
                    (tr "workspace.branches.resolve.keep-all-branch")]])
                [:> brl/group-head* {:label (tr "workspace.branches.review.group.decisions" (str total))
                                     :is-open true}]
                (for [c indexed]
                  [:> conflict-row* {:key (::d/index c)
                                     :conflict c
                                     :index (::d/index c)
                                     :selected sel-idx
                                     :res (get resolutions (:id c))
                                     :on-select on-select}])]))
      :detail
      (mf/html
       (cond
         loading?
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/git-merge :text (tr "workspace.branches.compare.loading")}]]

         (empty? conflicts)
         [:div {:class (stl/css :empty)}
          [:> empty-state* {:icon i/git-merge :text (tr "workspace.branches.conflicts.empty")}]]

         sel
         [:> conflict-detail* {:key (str (:id sel))
                               :conflict sel
                               :res sel-res
                               :diff-meta diff-meta
                               :copies copies
                               :data data
                               :on-pick on-pick
                               :on-choose on-choose
                               :on-clear on-clear}]))
      :footer-start
      (mf/html
       (if all-done?
         [:div {:class (stl/css :footer-start)}
          [:> text* {:as "span" :typography t/body-small :class (stl/css :footer-ready)}
           (tr "workspace.branches.conflicts.footer-ready")]]
         [:> text* {:as "span" :typography t/body-small :class (stl/css :footer-blocked)}
          (tr "workspace.branches.resolve.footer.blocked" (i18n/c pending))]))
      :footer-end
      (mf/html
       [:*
        [:> button* {:variant "secondary" :on-click on-back}
         (tr "workspace.branches.resolve.back")]
        (if all-done?
          [:> button* {:variant "primary" :icon i/git-merge :on-click on-apply}
           (tr "workspace.branches.conflicts.apply")]
          [:> button* {:variant "primary" :on-click on-next}
           (tr "workspace.branches.resolve.next")])])
      :on-close on-close}]))
