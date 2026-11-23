;; This Source Code Form is subject to the terms of the Mozilla Public
;; License v. 2.0. If a copy of the MPL was not distributed with this
;; file You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.branch-review.detail
  "The detail of the entry chosen in a comparison. A change says what it
  did, property by property, as `before → after`, and what else comes
  with it. A conflict shows the two sides of each property: only the
  ones really in conflict ask for a choice, the ones a single side
  changed are filled in from that side, and the detail says what each
  choice drops before it is applied."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.branch-merge :as bm]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.branch-pill :refer [branch-pill*]]
   [app.main.ui.ds.product.tag :refer [tag*]]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [app.main.ui.workspace.sidebar.branch-review.facts :as brf]
   [app.main.ui.workspace.sidebar.branch-review.layout :as brl]
   [app.util.clipboard :as clipboard]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Head

(defn- place
  "Where entry `item` is, in words: its page and top board for a layer,
  the name of its type for anything else."
  [item data]
  (if (= :shape (:kind item))
    (let [page  (get-in data [:pages-index (:page-id item)])
          board (brc/top-board (:objects page) (:id item))]
      (str/join " / " (remove nil? [(:name page) (:name board)])))
    (brc/type-label item)))

(mf/defc detail-head*
  "The name of the entry with its tags (`children`) and, under them, where
  it is and what happened to it."
  {::mf/private true}
  [{:keys [title place summary children]}]
  [:div {:class (stl/css :head)}
   [:div {:class (stl/css :head-row)}
    [:> heading* {:level 3 :typography t/body-large :class (stl/css :title)} title]
    children]
   [:> text* {:as "p" :typography t/body-small :class (stl/css :summary)}
    (str/join " - " (remove str/blank? [place summary]))]])

(mf/defc status-tag*
  {::mf/private true}
  [{:keys [status]}]
  (case status
    :added    [:> tag* {:tone "success"} (tr "workspace.branches.status.added")]
    :deleted  [:> tag* {:tone "error"} (tr "workspace.branches.status.deleted")]
    :conflict [:> tag* {:tone "warning"} (tr "workspace.branches.status.conflict")]
    :settled  [:> tag* {} (tr "workspace.branches.review.copies.settled-tag")]
    [:> tag* {:tone "info"} (tr "workspace.branches.status.modified")]))

;; --- A change

(mf/defc change-detail*
  "What the change `item` did. `on-edit` leaves the comparison to edit its
  layer and `on-toggle` leaves the change out of the merge or takes it
  back in; each button shows only with its handler."
  [{:keys [item items inner copies data open-side incoming excluded on-edit on-toggle on-resolve]}]
  (let [visible   (brc/visible-attrs (:changed-attrs item))
        conflict? (brc/conflict? item)
        copies-of (mf/with-memo [copies item data]
                    (brc/copies-entry copies item
                                      (get-in data [:pages-index (:page-id item) :objects])))]
    [:div {:class (stl/css :detail)}
     [:> detail-head* {:title (:label item)
                       :place (place item data)
                       :summary (when-not (bm/settled? item) (brc/change-summary item))}
      [:> status-tag* {:status (:status item)}]
      (when excluded
        [:> tag* {:tone "info"} (tr "workspace.branches.review.row.left-out")])]

     (when conflict?
       [:> brl/notice* {:level :warning}
        (tr "workspace.branches.review.decision.body")])

     ;; a copy the copy rule settled changes nothing: its section below
     ;; says what the rule decided instead
     (when-not (bm/settled? item)
       (if (seq visible)
         [:div {:class (stl/css :section)}
          [:> brl/value-steps* {:attrs visible :incoming incoming}]]
         (when (= :modified (:status item))
           [:> text* {:as "p" :typography t/body-small :class (stl/css :summary)}
            (tr "workspace.branches.compare.no-props")])))

     ;; the copy rule: what it decided on this copy, how many copies a
     ;; choice on a main decides, and which copies follow the component
     (when (:settled item)
       [:> brf/settled-facts* {:settled (:settled item)
                               :is-conflict conflict?
                               :is-settled (bm/settled? item)}])
     (when (and conflict? (:copies-affected item))
       [:> brf/copies-affected* {:n (:copies-affected item)}])
     (when copies-of
       [:> brf/copies-facts* {:entry copies-of :copies copies :data data}])

     ;; an entry without a before and after says what it is, where it
     ;; lands and what it affects instead
     (when-not conflict?
       (case (:kind item)
         :page
         (when (contains? #{:added :deleted} (:status item))
           [:> brf/page-facts* {:item item :data data :incoming incoming}])

         :shape
         (when (contains? #{:added :deleted} (:status item))
           [:> brf/shape-facts* {:item item :items items :data data :inner inner
                                 :open-side open-side :incoming incoming}])

         (:component :color :typography :token)
         [:> brf/usage-facts* {:item item :data data}]

         nil))

     (when (or on-edit on-toggle on-resolve)
       [:div {:class (stl/css :section :stacked-actions)}
        (when on-edit
          [:> button* {:variant "secondary" :class (stl/css :wide-action) :on-click on-edit}
           (tr "workspace.compare.action.edit-layer")])
        (when on-toggle
          [:> button* {:variant "secondary" :class (stl/css :wide-action) :on-click on-toggle}
           (cond
             (and excluded incoming) (tr "workspace.compare.action.include-update")
             excluded                (tr "workspace.compare.action.include-merge")
             incoming                (tr "workspace.compare.action.leave-out-update")
             :else                   (tr "workspace.compare.action.leave-out-merge"))])
        (when on-resolve
          [:> button* {:variant "secondary" :class (stl/css :wide-action) :on-click on-resolve}
           (tr "workspace.branches.review.row.choose")])])]))

;; --- A choice between the two sides

(mf/defc side-pill*
  "The name of a side: main, or the branch by its name."
  [{:keys [side branch-name is-review]}]
  (if (= side :source)
    [:> branch-pill* {:label (tr "workspace.branches.main") :is-main true}]
    [:> branch-pill* {:label (or branch-name (tr "workspace.branches.review.side.branch"))
                      :is-review (boolean is-review)}]))

(mf/defc option-card*
  "One of the two sides of a choice, as a radio card: the side it keeps,
  what that side holds (`children`) and what keeping it costs
  (`warning`). `on-preview` hears when the pointer enters (with the side)
  and leaves (with nil)."
  [{:keys [side branch-name is-review is-chosen warning on-choose on-preview children]}]
  (let [on-click (mf/use-fn (mf/deps side on-choose) #(on-choose side))
        on-enter (mf/use-fn (mf/deps side on-preview) #(when on-preview (on-preview side)))
        on-leave (mf/use-fn (mf/deps on-preview) #(when on-preview (on-preview nil)))]
    [:button {:class (stl/css-case :option true :is-chosen is-chosen)
              :role "radio"
              :aria-checked (boolean is-chosen)
              :on-click on-click
              :on-pointer-enter on-enter
              :on-pointer-leave on-leave}
     [:> side-pill* {:side side :branch-name branch-name :is-review is-review}]
     children
     (when warning
       [:span {:class (stl/css :option-warning)}
        [:> i/icon* {:icon-id i/triangle-alert :size "s"}]
        [:> text* {:as "span" :typography t/body-small} warning]])]))

(mf/defc attr-options*
  "The two values of the property `attr` of a conflict, to keep one."
  [{:keys [conflict res attr branch-name is-review on-pick on-preview]}]
  (let [choice    (brc/attr-choice res attr)
        values    (get-in conflict [:changed-attrs attr])
        on-choose (mf/use-fn (mf/deps attr on-pick) #(on-pick attr %))]
    [:div {:class (stl/css :options) :role "radiogroup"}
     (for [side [:source :branch]]
       [:> option-card* {:key (name side)
                         :side side
                         :branch-name branch-name
                         :is-review is-review
                         :is-chosen (= choice side)
                         :on-choose on-choose
                         :on-preview on-preview}
        [:> brl/value-chip* {:value (get values side)}]])]))

(mf/defc text-options*
  "The two texts of a text conflict, to keep one whole: Penpot keeps one
  text, so each card counts its paragraphs and says which edits of the
  other side it drops. `show-paragraphs` lists them too."
  [{:keys [conflict res branch-name is-review show-paragraphs on-pick on-preview]}]
  (let [choice    (brc/attr-choice res :content)
        on-choose (mf/use-fn (mf/deps on-pick) #(on-pick :content %))]
    [:div {:class (stl/css :options) :role "radiogroup"}
     (for [side [:source :branch]
           :let [rows (brc/paragraph-rows conflict side)
                 lost (brc/dropped-paragraphs conflict side)]]
       [:> option-card* {:key (name side)
                         :side side
                         :branch-name branch-name
                         :is-review is-review
                         :is-chosen (= choice side)
                         :warning (when (seq lost)
                                    (tr "workspace.branches.resolve.drops-paragraphs"
                                        (str/join ", " (map #(dm/str "¶" (inc (:index %))) lost))))
                         :on-choose on-choose
                         :on-preview on-preview}
        (if show-paragraphs
          [:div {:class (stl/css :paragraphs)}
           (for [{:keys [index text status]} rows]
             [:> text* {:key index
                        :as "span"
                        :typography t/body-small
                        :class (stl/css-case :paragraph true
                                             :is-kept (= status :kept)
                                             :is-dropped (= status :dropped))}
              (dm/str "¶" (inc index) " " text)])]
          [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
           (tr "workspace.branches.resolve.paragraphs" (i18n/c (count rows)))])])]))

(mf/defc whole-options*
  "The choice for a conflict that cannot be split by property: one side
  deleted the entity, or both added it differently."
  [{:keys [conflict res branch-name is-review on-choose on-preview]}]
  [:div {:class (stl/css :options) :role "radiogroup"}
   (for [side [:source :branch]]
     [:> option-card* {:key (name side)
                       :side side
                       :branch-name branch-name
                       :is-review is-review
                       :is-chosen (= res side)
                       :warning (when (nil? (get conflict side))
                                  (tr "workspace.branches.resolve.deletes-it"))
                       :on-choose on-choose
                       :on-preview on-preview}
      [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
       (if (= side :source)
         (tr "workspace.branches.resolve.keep-main-version")
         (tr "workspace.branches.resolve.keep-branch-version"))]])])

;; --- A conflict

(mf/defc pick-button*
  "The value of one side of a property: a click keeps it."
  {::mf/private true}
  [{:keys [attr side value choice is-conflicting on-pick]}]
  (let [on-click (mf/use-fn (mf/deps attr side on-pick) #(on-pick attr side))]
    [:button {:class (stl/css-case :pick true :is-chosen (and is-conflicting (= choice side)))
              :disabled (not is-conflicting)
              :on-click on-click}
     [:> brl/value-chip* {:value value :is-old (not= choice side)}]]))

(mf/defc attr-table*
  "Each property of a conflict with the value of main and of the branch,
  and where its result comes from: a conflict, the user's choice, or the
  only side that changed it."
  {::mf/private true}
  [{:keys [rows res branch-name selected on-pick on-select]}]
  [:div {:class (stl/css :attrs)}
   (for [{:keys [attr source branch origin]} rows
         :let [choice       (brc/attr-choice res attr)
               conflicting? (= origin :conflict)]]
     [:div {:class (stl/css-case :attr true
                                 :is-conflicting (and conflicting? (nil? choice))
                                 :is-selected (= attr selected))
            :key (str attr)
            :data-attr (name attr)
            :on-click (when conflicting? on-select)}
      [:div {:class (stl/css :attr-head)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :attr-name)}
        (brc/attr-label attr)]
       (cond
         ;; a decided conflict is done (green); an independent edit names
         ;; the side it comes from
         (and conflicting? choice)
         [:> tag* {:tone "success"} (tr "workspace.branches.resolve.tag.your-choice")]
         conflicting?
         [:> tag* {:tone "warning"} (tr "workspace.branches.resolve.tag.conflict")]
         (= origin :source)
         [:> tag* {:tone "purple"}
          (tr "workspace.branches.resolve.tag.from" (tr "workspace.branches.compare.main"))]
         :else
         [:> tag* {:tone "accent"}
          (tr "workspace.branches.resolve.tag.from"
              (or branch-name (tr "workspace.branches.compare.branch")))])]
      [:div {:class (stl/css :attr-values)}
       [:> pick-button* {:attr attr
                         :side :source
                         :value source
                         :choice (or choice (when-not conflicting? origin))
                         :is-conflicting conflicting?
                         :on-pick on-pick}]
       [:> pick-button* {:attr attr
                         :side :branch
                         :value branch
                         :choice (or choice (when-not conflicting? origin))
                         :is-conflicting conflicting?
                         :on-pick on-pick}]]])])

(mf/defc outcome*
  "Once decided, what the merge or update leaves behind, with the way
  back; until then, how the choice works."
  {::mf/private true}
  [{:keys [conflict res on-clear]}]
  (let [drops (brc/dropped-values conflict res)]
    (if (bm/conflict-resolved? conflict res)
      [:div {:class (stl/css :outcome)}
       [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
        (tr "workspace.branches.resolve.drops.title")]
       (if (seq drops)
         (for [{:keys [attr side value]} drops]
           [:> text* {:key (str attr)
                      :as "span"
                      :typography t/body-small
                      :class (stl/css :outcome-line)}
            (if (= side :source)
              (tr "workspace.branches.resolve.drops.main-short" (str/lower (brc/attr-label attr)))
              (tr "workspace.branches.resolve.drops.branch-short" (str/lower (brc/attr-label attr))))
            " "
            [:span {:class (stl/css :outcome-dropped)} (brc/display-val value)]])
         [:> text* {:as "span" :typography t/body-small :class (stl/css :outcome-line)}
          (tr "workspace.branches.resolve.drops.none")])
       [:> button* {:variant "secondary" :class (stl/css :outcome-action) :on-click on-clear}
        (tr "workspace.branches.resolve.change-choice")]]

      [:> text* {:as "p" :typography t/body-small :class (stl/css :summary)}
       (cond
         (brc/text-conflict? conflict) (tr "workspace.branches.resolve.intro-text")
         (brc/per-attr? conflict)      (tr "workspace.branches.resolve.intro-attrs")
         :else                         (tr "workspace.branches.resolve.intro-whole"))])))

(mf/defc conflict-detail*
  "The conflict `conflict` under resolution `res`. `attr` is the property
  in turn; `on-pick` keeps a side for a property, `on-choose` for the
  whole entity, `on-clear` forgets the choice and `on-attr` takes another
  property in turn."
  [{:keys [conflict res attr branch-name is-review copies data
           on-pick on-choose on-clear on-attr]}]
  (let [rows       (brc/conflict-attr-rows conflict)
        copies-of  (mf/with-memo [copies conflict data]
                     (brc/copies-entry copies conflict
                                       (get-in data [:pages-index (:page-id conflict) :objects])))
        per-attr?  (brc/per-attr? conflict)
        text?      (brc/text-conflict? conflict)
        table-rows (if text? (filterv #(not= :content (:attr %)) rows) rows)
        conflict-n (count (brc/conflicting-attrs rows))
        indep-n    (- (count rows) conflict-n)
        resolved?  (bm/conflict-resolved? conflict res)
        lost       (when text?
                     (some->> (brc/attr-choice res :content)
                              (brc/dropped-paragraphs conflict)))
        on-select  (mf/use-fn
                    (mf/deps on-attr)
                    (fn [event]
                      (on-attr (-> (dom/get-current-target event)
                                   (dom/get-data "attr")
                                   (keyword)))))
        on-copy    (mf/use-fn
                    (mf/deps lost)
                    (fn []
                      (-> (clipboard/to-clipboard (str/join "\n" (map :text lost)))
                          (.catch (constantly nil)))))]
    [:div {:class (stl/css :detail)}
     [:> detail-head*
      {:title (:label conflict)
       :place (place conflict data)
       :summary (if per-attr?
                  (dm/str (tr "workspace.branches.resolve.tag.conflicts" (i18n/c conflict-n))
                          (when (pos? indep-n)
                            (dm/str ", " (tr "workspace.branches.resolve.tag.independent"
                                             (i18n/c indep-n)))))
                  (brc/conflict-reason-label (:reason conflict)))}
      (if resolved?
        [:> tag* {:tone "success"} (tr "workspace.branches.resolve.decided")]
        [:> tag* {:tone "warning"} (tr "workspace.branches.status.conflict")])]

     ;; which value is whose: main first, then the branch
     [:div {:class (stl/css :legend)}
      [:> side-pill* {:side :source}]
      [:> side-pill* {:side :branch :branch-name branch-name :is-review is-review}]]

     (cond
       (not per-attr?)
       [:> whole-options* {:conflict conflict :res res
                           :branch-name branch-name :is-review is-review
                           :on-choose on-choose}]

       text?
       [:*
        [:> text-options* {:conflict conflict :res res
                           :branch-name branch-name :is-review is-review
                           :show-paragraphs true
                           :on-pick on-pick}]
        (when (seq lost)
          [:div {:class (stl/css :actions)}
           [:> text* {:as "span" :typography t/body-small :class (stl/css :muted)}
            (tr "workspace.branches.resolve.keep-safe")]
           [:> button* {:variant "secondary" :on-click on-copy}
            (tr "workspace.branches.resolve.copy-dropped")]])])

     (when (and per-attr? (seq table-rows))
       [:> attr-table* {:rows table-rows
                        :res res
                        :branch-name branch-name
                        :selected attr
                        :on-pick on-pick
                        :on-select on-select}])

     ;; the copy rule: what it decided on this copy, how many copies a
     ;; choice on a main decides, and which copies follow the component
     (when (:settled conflict)
       [:> brf/settled-facts* {:settled (:settled conflict) :is-conflict true}])
     (when (:copies-affected conflict)
       [:> brf/copies-affected* {:n (:copies-affected conflict)}])
     (when copies-of
       [:> brf/copies-facts* {:entry copies-of :copies copies :data data}])

     [:> outcome* {:conflict conflict :res res :on-clear on-clear}]]))
