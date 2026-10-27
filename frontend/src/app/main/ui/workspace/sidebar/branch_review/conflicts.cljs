;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.branch-review.conflicts
  "The decisions dialog: one entity both sides changed at a time. Only the
  properties that really clash ask for a choice; the ones a single side
  changed are filled in from that side, and the dialog says what each
  choice drops before the merge applies it."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.branches :as dwb]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
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
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.main.ui.workspace.sidebar.branch-review.previews :as brp]
   [app.util.clipboard :as clipboard]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private branch-diff
  (l/derived :workspace-branch-diff st/state))

(def ^:private reason->label
  {:modify-modify "workspace.branches.conflicts.reason.modify-modify"
   :add-add       "workspace.branches.conflicts.reason.add-add"
   :delete-modify "workspace.branches.conflicts.reason.delete-modify"
   :modify-delete "workspace.branches.conflicts.reason.modify-delete"})

(defn- text-clash?
  "A conflict whose text content both sides rewrote."
  [conflict]
  (and (brc/per-attr? conflict)
       (some #(and (= :content (:attr %)) (= :clash (:source %)))
             (brc/conflict-attr-rows conflict))))

(defn- loses-work?
  "Whether every choice on a text conflict drops a paragraph edit: both
  sides edited different paragraphs."
  [conflict]
  (and (text-clash? conflict)
       (some #(= :dropped (:status %)) (brc/paragraph-rows conflict :main))
       (some #(= :dropped (:status %)) (brc/paragraph-rows conflict :branch))))

(defn- row-subtitle
  [conflict res]
  (let [rows   (brc/conflict-attr-rows conflict)
        clash  (brc/clash-attrs rows)
        drops  (brc/dropped-values conflict res)]
    (cond
      (bm/conflict-resolved? conflict res)
      (cond
        (= res :main)   (tr "workspace.branches.resolve.row.kept-main")
        (= res :branch) (tr "workspace.branches.resolve.row.kept-branch")
        (seq drops)     (tr "workspace.branches.resolve.row.drops"
                            (str/lower (str/join ", " (map (comp brc/attr-label :attr) drops))))
        :else           (tr "workspace.branches.resolve.row.kept-both"))

      (seq clash)
      (tr "workspace.branches.resolve.row.both-changed"
          (str/join ", " (map brc/attr-label clash)))

      :else
      (some-> (get reason->label (:reason conflict)) tr))))

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

(mf/defc attr-table*
  "Property | Main | Branch | Result, with the source of each result."
  {::mf/private true}
  [{:keys [rows res on-pick]}]
  [:div {:class (stl/css :table)}
   [:div {:class (stl/css :table-row :is-head)}
    (for [k ["workspace.branches.resolve.th.property"
             "workspace.branches.resolve.th.main"
             "workspace.branches.resolve.th.branch"
             "workspace.branches.resolve.th.result"]]
      [:> text* {:key k :as "span" :typography t/headline-small :class (stl/css :th)} (tr k)])
    [:span]]
   (for [{:keys [attr main branch source]} rows
         :let [choice (brc/attr-choice res attr)
               clash? (= source :clash)
               result (case (or choice (when-not clash? source))
                        :main   main
                        :branch branch
                        nil)
               decided? (some? (or choice (when-not clash? source)))]]
     [:div {:class (stl/css-case :table-row true :is-clash (and clash? (nil? choice)))
            :key (str attr)}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :td-name)}
       (brc/attr-label attr)]
      (if (= source :branch)
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-unchanged)}
         (tr "workspace.branches.resolve.unchanged")]
        [:button {:class (stl/css-case :td-pick true :is-chosen (and clash? (= choice :main)))
                  :disabled (not clash?)
                  :on-click #(on-pick attr :main)}
         [:> brl/value-chip* {:value main :is-old (not= choice :main)}]])
      (if (= source :main)
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-unchanged)}
         (tr "workspace.branches.resolve.unchanged")]
        [:button {:class (stl/css-case :td-pick true :is-chosen (and clash? (= choice :branch)))
                  :disabled (not clash?)
                  :on-click #(on-pick attr :branch)}
         [:> brl/value-chip* {:value branch :is-old (not= choice :branch)}]])
      (if decided?
        [:span {:class (stl/css :td-result)}
         [:> brl/value-chip* {:value result}]]
        [:> text* {:as "span" :typography t/body-small :class (stl/css :td-choose)}
         (tr "workspace.branches.resolve.choose-side")])
      [:span {:class (stl/css :td-tag)}
       (cond
         ;; a decided clash is done (green); an independent edit takes the
         ;; colour of the side it comes from, as the preview cards do
         (and clash? choice)
         [:> tag* {:tone "success"} (tr "workspace.branches.resolve.tag.your-choice")]
         clash?
         [:> tag* {:tone "warning"} (tr "workspace.branches.resolve.tag.clash")]
         (= source :main)
         [:> tag* {:tone "purple"} (tr "workspace.branches.resolve.tag.from-main")]
         :else
         [:> tag* {:tone "accent"} (tr "workspace.branches.resolve.tag.from-branch")])]])])

;; --- Whole-version and text choices

(mf/defc option-card*
  "A choice between the two versions, as a radio card."
  {::mf/private true}
  [{:keys [label side detail is-chosen warning on-choose children]}]
  [:button {:class (stl/css-case :option true :is-chosen is-chosen)
            :role "radio"
            :aria-checked (boolean is-chosen)
            :on-click on-choose}
   [:div {:class (stl/css :option-head)}
    [:span {:class (stl/css-case :option-radio true :is-chosen is-chosen) :aria-hidden true}]
    [:> text* {:as "span" :typography t/body-small :class (stl/css :option-label)} label]]
   [:div {:class (stl/css :option-side)}
    [:> text* {:as "span" :typography t/headline-small
               :class (stl/css-case :option-side-label true :is-main (= side :main))}
     (tr (if (= side :main)
           "workspace.branches.review.side.main"
           "workspace.branches.review.side.branch"))]
    (when detail
      [:> text* {:as "span" :typography t/body-small :class (stl/css :option-detail)} detail])]
   children
   (when warning
     [:span {:class (stl/css :option-warning)}
      [:> i/icon* {:icon-id i/triangle-alert :size "s"}]
      warning])])

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
                          :label (tr (if (= side :main)
                                       "workspace.branches.resolve.keep-main-text"
                                       "workspace.branches.resolve.keep-branch-text"))
                          :side side
                          :detail (if (= side :main) main-detail branch-detail)
                          :is-chosen (= choice side)
                          :warning (when (seq lost)
                                     (tr "workspace.branches.resolve.drops-paragraphs"
                                         (str/join ", " (map #(dm/str "¶" (inc (:index %))) lost))))
                          :on-choose #(on-pick :content side)}
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
                       :label (tr (if (= side :main)
                                    "workspace.branches.resolve.keep-main-version"
                                    "workspace.branches.resolve.keep-branch-version"))
                       :side side
                       :detail (if (= side :main) main-detail branch-detail)
                       :is-chosen (= res side)
                       :warning (when (nil? value)
                                  (tr "workspace.branches.resolve.deletes-it"))
                       :on-choose #(on-choose side)}
      [:div {:class (stl/css :option-stage)}
       [:> brp/entity-preview* {:kind (:kind conflict)
                                :value value
                                :missing {:reason :deleted :side side}}]]])])

;; --- Detail

(mf/defc conflict-detail*
  {::mf/private true}
  [{:keys [conflict res diff-meta on-pick on-choose on-clear]}]
  (let [rows       (brc/conflict-attr-rows conflict)
        per-attr?  (brc/per-attr? conflict)
        text?      (text-clash? conflict)
        table-rows (if text? (filterv #(not= :content (:attr %)) rows) rows)
        clash-n    (count (brc/clash-attrs rows))
        indep-n    (- (count rows) clash-n)
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
           (dm/str (tr "workspace.branches.resolve.tag.clashes" (i18n/c clash-n))
                   (when (pos? indep-n)
                     (dm/str " - " (tr "workspace.branches.resolve.tag.independent" (i18n/c indep-n)))))
           (tr (get reason->label (:reason conflict) "workspace.branches.status.conflict")))])]

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
             :when (and (= :clash source) (not= attr :content))
             :let [choice (brc/attr-choice res attr)]]
         [:div {:class (stl/css :inline-actions) :key (str attr)}
          [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
           (dm/str (brc/attr-label attr) ":")]
          [:> button* {:variant (if (= choice :main) "primary" "secondary")
                       :on-click #(on-pick attr :main)}
           (tr "workspace.branches.resolve.keep-main" (brc/display-val main))]
          [:> button* {:variant (if (= choice :branch) "primary" "secondary")
                       :on-click #(on-pick attr :branch)}
           (tr "workspace.branches.resolve.keep-branch" (brc/display-val branch))]]))

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
              (tr (if (= side :main)
                    "workspace.branches.resolve.drops.main-short"
                    "workspace.branches.resolve.drops.branch-short")
                  (str/lower (brc/attr-label attr)))
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
  [{:keys [branch mode conflicts excluded]}]
  (let [{:keys [diff resolutions status]} (mf/deref branch-diff)

        loading?    (= status :loading)
        keep?*      (mf/use-state false)
        keep?       (deref keep?*)
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
                     (mf/deps branch resolutions mode keep? excluded conflicts)
                     (fn []
                       (let [ids (into #{} (map :id) conflicts)
                             res (into {} (filter (fn [[id _]] (contains? ids id))) resolutions)]
                         (st/emit! (if (= mode :update)
                                     (dwb/update-branch-from-main branch {:resolutions res
                                                                          :excluded excluded})
                                     (dwb/merge-branch branch {:resolutions res
                                                               :keep-branch keep?
                                                               :excluded excluded}))))))]

    ;; the fetched diff backs both the fallback conflict set and the side
    ;; subtitles, so it must be computed in the direction this modal resolves
    (mf/with-effect [(:id branch) mode]
      (st/emit! (dwb/fetch-branch-diff (:id branch)
                                       (if (= mode :update) :main->branch :branch->main))))

    ;; independent edits are not in doubt: fill them in from the side that
    ;; made them, so only the clashing properties wait for a choice
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
                   [:> button* {:variant "secondary" :on-click #(on-all :main)}
                    (tr "workspace.branches.resolve.keep-all-main")]
                   [:> button* {:variant "secondary" :on-click #(on-all :branch)}
                    (tr "workspace.branches.resolve.keep-all-branch")]])
                [:> brl/group-head* {:label (tr "workspace.branches.review.group.decisions" (str total))
                                     :is-open true}]
                (for [[idx c] (map-indexed vector conflicts)]
                  [:> conflict-row* {:key idx
                                     :conflict c
                                     :index idx
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
                               :on-pick on-pick
                               :on-choose on-choose
                               :on-clear on-clear}]))
      :footer-start
      (mf/html
       (if all-done?
         [:div {:class (stl/css :footer-start)}
          [:> text* {:as "span" :typography t/body-small :class (stl/css :footer-ready)}
           (tr "workspace.branches.conflicts.footer-ready")]
          (when (not= mode :update)
            [:> checkbox* {:id "conflicts-archive-branch"
                           :label (tr "workspace.branches.merge.archive-label")
                           :checked keep?
                           :on-change #(swap! keep?* not)}])]
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
