;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.branch-review-common-test
  "What the compare mode says about a diff: how a conflict splits into the
  properties both sides moved and the ones a single side moved, what an
  entry is and affects, the list the changes make and the marks they
  leave on the canvas."
  (:require
   [app.common.files.branch-merge :as bm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.compare :as dwcp]
   [app.main.ui.workspace.sidebar.branch-review.common :as brc]
   [cljs.test :as t :include-macros true]))

(def ^:private card
  "Both sides edited the card: the radius on both, the width on the
  branch alone and the opacity on main alone."
  {:kind :shape
   :status :conflict
   :reason :modify-modify
   :base   {:rx 8 :width 320 :opacity 1}
   :main   {:rx 12 :width 320 :opacity 0.5}
   :branch {:rx 4 :width 360 :opacity 1}
   :changed-attrs {:rx      {:main 12 :branch 4}
                   :width   {:main 320 :branch 360}
                   :opacity {:main 0.5 :branch 1}}})

(defn- sources
  [conflict]
  (into {} (map (juxt :attr :source)) (brc/conflict-attr-rows conflict)))

(t/deftest only-properties-both-sides-moved-conflict
  (t/is (= {:rx :conflict :width :branch :opacity :main} (sources card))))

(t/deftest without-a-base-every-property-conflicts
  (t/is (= #{:conflict} (set (vals (sources (assoc card :base nil :reason :add-add))))))
  (t/is (= #{:conflict} (set (vals (sources (dissoc card :base)))))))

(t/deftest independent-edits-come-filled-in
  (let [res (brc/prefill-resolution card)]
    (t/is (= {:width :branch :opacity :main} res))
    (t/is (not (bm/conflict-resolved? card res))
          "the conflicting radius still waits for a choice")))

(t/deftest choosing-the-conflicting-property-resolves-the-conflict
  (let [res (brc/choose-attr card nil :rx :branch)]
    (t/is (= {:rx :branch :width :branch :opacity :main} res))
    (t/is (bm/conflict-resolved? card res))
    (t/is (= [{:attr :rx :side :main :value 12}]
             (brc/dropped-values card res))
          "keeping the branch radius drops main's, and nothing else")))

(t/deftest a-conflict-without-conflicting-properties-comes-out-resolved
  (let [conflict (-> card
                     (assoc-in [:main :rx] 8)
                     (update :changed-attrs dissoc :rx))]
    (t/is (bm/conflict-resolved? conflict (brc/prefill-resolution conflict)))))

(t/deftest bulk-choice-keeps-independent-edits
  (t/is (= {:rx :main :width :branch :opacity :main} (brc/choose-side card :main)))
  (t/is (= :branch (brc/choose-side (assoc card :changed-attrs {} :main nil) :branch))
        "an entity that cannot be split takes the whole side"))

(t/deftest paragraphs-say-which-edits-a-side-keeps-and-drops
  (let [text   (fn [& ps]
                 {:type "root"
                  :children [{:type "paragraph-set"
                              :children (mapv (fn [p] {:type "paragraph"
                                                       :children [{:text p}]})
                                              ps)}]})
        note   {:kind :shape
                :status :conflict
                :base   {:content (text "One" "Two" "Three")}
                :main   {:content (text "One step" "Two" "Three")}
                :branch {:content (text "One" "Two" "Three more")}
                :changed-attrs {:content {}}}]
    (t/is (= [:kept nil :dropped] (mapv :status (brc/paragraph-rows note :main))))
    (t/is (= [:dropped nil :kept] (mapv :status (brc/paragraph-rows note :branch))))))

;; --- What a new or deleted entry is, where it lands, what it affects

(def ^:private board-id #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private group-id #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private rect-id  #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private copy-id  #uuid "00000000-0000-0000-0000-000000000004")
(def ^:private comp-id  #uuid "00000000-0000-0000-0000-000000000005")
(def ^:private color-id #uuid "00000000-0000-0000-0000-000000000006")
(def ^:private page-id  #uuid "00000000-0000-0000-0000-000000000007")

(def ^:private objects
  {uuid/zero {:id uuid/zero :type :frame :shapes [board-id]}
   board-id  {:id board-id :type :frame :name "Checkout" :parent-id uuid/zero
              :frame-id uuid/zero :shapes [group-id copy-id]}
   group-id  {:id group-id :type :group :name "Summary" :parent-id board-id
              :frame-id board-id :shapes [rect-id]}
   rect-id   {:id rect-id :type :rect :name "Price" :parent-id group-id
              :frame-id board-id
              :fills [{:fill-color "#000000" :fill-color-ref-id color-id}]
              :applied-tokens {:fill "brand.primary"}}
   copy-id   {:id copy-id :type :frame :name "Button" :parent-id board-id
              :frame-id board-id :component-id comp-id}})

(def ^:private data
  {:pages ["p"]
   :pages-index {page-id {:id page-id :name "Page 1" :objects objects}}})

(t/deftest a-page-counts-its-boards-and-layers
  (let [{:keys [boards layers]} (brc/page-contents {:objects objects})]
    (t/is (= ["Checkout"] (map :name boards)))
    (t/is (= 4 layers) "the root frame does not count")))

(t/deftest a-layer-names-the-containers-around-it
  (t/is (= ["Checkout" "Summary"] (brc/shape-path objects rect-id)))
  (t/is (= [] (brc/shape-path objects board-id))))

(t/deftest library-entries-name-the-layers-that-use-them
  (let [ids (fn [item] (mapv (comp :id :shape) (brc/usages data item)))]
    (t/is (= [copy-id] (ids {:kind :component :id comp-id})))
    (t/is (= [rect-id] (ids {:kind :color :id color-id})))
    (t/is (= [rect-id] (ids {:kind :token :label "brand.primary"})))
    (t/is (= [] (ids {:kind :token :label "brand.secondary"})))
    (t/is (= page-id (:page-id (first (brc/usages data {:kind :color :id color-id})))))
    (t/is (nil? (brc/usages data {:kind :shape :id rect-id}))
          "a layer is no library entry")))

(t/deftest pages-get-their-own-group-before-the-library
  (let [groups (brc/group-by-place [{:kind :page :status :added :label "Page 2"}
                                    {:kind :color :status :added :label "Red"}]
                                   {} [])]
    (t/is (= ["pages" "library"] (map :id groups)))))

(t/deftest a-board-folds-only-two-or-more-changes
  (let [pages-index {page-id {:id page-id :name "Page 1" :objects objects}}
        rect   {:kind :shape :status :modified :id rect-id :page-id page-id}
        button {:kind :shape :status :modified :id copy-id :page-id page-id}
        one    (first (brc/group-by-place [rect] pages-index []))
        two    (first (brc/group-by-place [rect button] pages-index []))]
    (t/is (= [] (:boards one)) "a lone change stays loose")
    (t/is (= [rect] (:rows one)))
    (t/is (= ["Checkout"] (map :name (:boards two))))
    (t/is (= [] (:rows two)))))

(defn- deleted
  [id parent-id]
  {:kind :shape :status :deleted :id id :page-id page-id
   :shape {:id id :parent-id parent-id}})

(t/deftest a-deleted-group-takes-its-layers-along
  (let [icon  (deleted :icon :button)
        items [(deleted :path-a :icon)
               (deleted :bg :icon)
               (deleted :path-b :inner)
               (deleted :inner :icon)
               icon
               {:kind :shape :status :modified :id :label :page-id page-id}]
        folds (brc/fold-cascades items)]
    (t/is (= [(dwcp/item-key icon)] (keys folds))
          "the outermost deleted layer holds them all")
    (t/is (= #{:path-a :bg :path-b :inner}
             (set (map :id (get folds (dwcp/item-key icon))))))))

(t/deftest only-the-same-change-folds
  (let [added  {:kind :shape :status :added :id :new :page-id page-id
                :shape {:id :new :parent-id :icon}}
        items  [(deleted :icon :button)
                added
                (deleted :lone :button)
                (assoc (deleted :other :icon) :page-id :elsewhere)]]
    (t/is (= {} (brc/fold-cascades items))
          "an addition, a layer whose parent stays and one on another page stay apart")))

;; --- What goes with an added or deleted layer

(def ^:private home-id   (uuid/next))
(def ^:private screen-id (uuid/next))
(def ^:private cta-id    (uuid/next))
(def ^:private button-id (uuid/next))
(def ^:private icon-id   (uuid/next))
(def ^:private label-id  (uuid/next))

(def ^:private proto-objects
  "Main's page: a button with a flex layout on Home, and a Screen the
  CTA leads to."
  {uuid/zero {:id uuid/zero :type :frame :shapes [home-id screen-id]}
   home-id   {:id home-id :type :frame :name "Home" :parent-id uuid/zero}
   screen-id {:id screen-id :type :frame :name "Screen" :parent-id uuid/zero}
   cta-id    {:id cta-id :type :rect :name "CTA" :parent-id home-id}
   button-id {:id button-id :type :frame :name "Button" :parent-id home-id
              :layout :flex :width 163 :height 48}
   icon-id   {:id icon-id :type :group :name "icon" :parent-id button-id}
   label-id  {:id label-id :type :text :name "label" :parent-id button-id}})

(defn- gone
  [id]
  {:kind :shape :status :deleted :id id :page-id page-id})

(defn- edit
  [id attrs]
  {:kind :shape :status :modified :id id :page-id page-id :label (name id)
   :changed-attrs attrs})

(t/deftest a-board-with-a-layout-resizes-with-the-layer
  (let [items [(gone icon-id)
               (edit button-id {:width {:main 163 :branch 131}})]
        fx    (brc/shape-effects (gone icon-id) items proto-objects [] false)]
    (t/is (= [{:id button-id :name "Button" :layout :flex :from [163 48] :to [131 48]}]
             (:layouts fx)))
    (t/is (= [] (:interactions fx)))
    (t/is (= [] (:flows fx)))))

(t/deftest an-update-reads-the-sides-the-other-way-round
  (let [items [(gone icon-id)
               (edit button-id {:width {:main 131 :branch 163}})]
        fx    (brc/shape-effects (gone icon-id) items proto-objects [] true)]
    (t/is (= [[163 48] [131 48]] ((juxt :from :to) (first (:layouts fx)))))))

(t/deftest a-fixed-board-moves-its-other-layers
  (let [items [(gone icon-id)
               (edit label-id {:x {:main 40 :branch 20}})]
        fx    (brc/shape-effects (gone icon-id) items proto-objects [] false)]
    (t/is (= [{:id button-id :name "Button" :layout :flex :moved 1}] (:layouts fx)))))

(t/deftest prototype-links-into-a-deleted-board-go-with-it
  (let [link  {:event-type :click :action-type :navigate :destination screen-id}
        items [(gone screen-id)
               (assoc (edit cta-id {:interactions {:main [link] :branch []}}) :label "CTA")
               {:kind :page-flow :status :deleted :id :f1 :page-id page-id :label "f1"
                :flow {:id :f1 :name "Onboarding" :starting-frame screen-id}}
               {:kind :page-flow :status :deleted :id :f2 :page-id page-id :label "f2"
                :flow {:id :f2 :name "Other" :starting-frame home-id}}]
        fx    (brc/shape-effects (gone screen-id) items proto-objects [] false)]
    (t/is (= [{:id cta-id :label "CTA" :count 1}] (:interactions fx)))
    (t/is (= ["Onboarding"] (:flows fx)))
    (t/is (= [] (:layouts fx)) "the page root is not a layout")))

(t/deftest an-added-layer-breaks-no-links
  (let [link  {:event-type :click :action-type :navigate :destination screen-id}
        added (assoc (gone screen-id) :status :added)
        items [added (edit cta-id {:interactions {:main [] :branch [link]}})]
        fx    (brc/shape-effects added items proto-objects [] false)]
    (t/is (= [] (:interactions fx)))))

;; --- The list of a comparison

(defn- change
  "A clean change of the layer `id` of the page, with its change key."
  [id status]
  {:kind :shape :status status :id id :page-id page-id :label (name id)
   :key (str "shape/" page-id "/" (name id))})

(def ^:private red
  {:kind :color :status :modified :id color-id :label "Red"
   :key (str "color//" color-id)})

(def ^:private clash
  (assoc (change :clash :conflict) :changed-attrs {:rx {:main 1 :branch 2}}))

(def ^:private diff
  {:changes [(change :title :modified)
             (assoc (change :icon :deleted) :shape {:id :icon :parent-id :button})
             (assoc (change :path :deleted) :shape {:id :path :parent-id :icon})
             red]
   :conflicts [clash]})

(defn- model
  [opts]
  (brc/change-model diff (merge {:filter :all :pages-index {} :pages []} opts)))

(t/deftest the-list-shows-decisions-first-and-folds-what-follows
  (let [{:keys [rows cascades decisions counts kept left-out]} (model {})]
    (t/is (= ["clash" "title" "icon" "Red"] (map :label rows))
          "the conflict first; the path goes under the icon deleted with it")
    (t/is (= [:path] (map :id (get cascades (dwcp/item-key (change :icon :deleted))))))
    (t/is (= 1 decisions))
    (t/is (= {:all 4 :layers 3 :library 1 :left-out 0} counts))
    (t/is (= 3 kept) "a conflict is decided, not selected")
    (t/is (= 0 left-out))))

(t/deftest the-filters-keep-layers-library-or-what-is-left-out
  (t/is (= ["clash" "title" "icon"] (map :label (:rows (model {:filter :layers})))))
  (t/is (= ["Red"] (map :label (:rows (model {:filter :library})))))
  (let [out (model {:filter :left-out :excluded #{(:key red)}})]
    (t/is (= ["Red"] (map :label (:rows out))))
    (t/is (= 1 (:left-out out)))
    (t/is (= 2 (:kept out)))
    (t/is (= 4 (get-in out [:counts :all])) "a filter changes what shows, not what there is")))

(t/deftest a-row-takes-its-folded-layers-along
  (let [icon (change :icon :deleted)]
    (t/is (= #{(:key icon) (:key (change :path :deleted))}
             (brc/row-keys icon [(change :path :deleted)])))))

(t/deftest the-list-is-walked-round
  (let [rows (:rows (model {}))
        k    #(dwcp/item-key (nth rows %))]
    (t/is (= (k 0) (brc/step rows nil 1)) "nothing chosen: the first row")
    (t/is (= (k 1) (brc/step rows (k 0) 1)))
    (t/is (= (k 3) (brc/step rows (k 0) -1)) "before the first comes the last")
    (t/is (= (k 0) (brc/step rows (k 3) 1)))
    (t/is (nil? (brc/step [] nil 1)))))

(t/deftest the-next-pending-row-skips-the-ones-done
  (let [rows  (:rows (model {}))
        k     #(dwcp/item-key (nth rows %))
        done  #{(k 1) (k 2)}
        done? #(contains? done (dwcp/item-key %))]
    (t/is (= (k 3) (brc/next-pending rows (k 0) done?)))
    (t/is (= (k 0) (brc/next-pending rows (k 3) done?)) "it goes round")
    (t/is (nil? (brc/next-pending rows (k 0) (constantly true))))))

;; --- The marks on the canvas

(t/deftest a-change-is-marked-where-its-layer-is
  (let [objects {rect-id {:id rect-id :selrect {:x 10 :y 20 :width 30 :height 40}}}
        here    {:kind :shape :status :modified :id rect-id :page-id page-id
                 :label "Price" :key "here"}
        gone    {:kind :shape :status :deleted :id copy-id :page-id page-id
                 :label "Button" :key "gone"
                 :shape {:id copy-id :selrect {:x 1 :y 2 :width 3 :height 4}}}
        away    (assoc here :page-id (uuid/next) :key "away")
        marks   (brc/change-marks [here gone away red]
                                  {:page-id page-id :objects objects})]
    (t/is (= [{:key "here" :label "Price" :status :modified
               :rect {:x 10 :y 20 :width 30 :height 40}}
              {:key "gone" :label "Button" :status :deleted
               :rect {:x 1 :y 2 :width 3 :height 4}}]
             marks)
          "a layer the open file lacks is marked where the diff says it was")
    (t/is (= ["gone"]
             (map :key (brc/change-marks [here gone] {:page-id page-id
                                                      :objects objects
                                                      :excluded #{"here"}})))
          "a change left out leaves no mark")
    (t/is (= [:decided :decided]
             (map :status (brc/change-marks [here gone] {:page-id page-id
                                                         :objects objects
                                                         :status-of (constantly :decided)}))))))

(t/deftest a-library-change-is-marked-on-the-layers-that-use-it
  (let [objects (assoc-in objects [rect-id :selrect] {:x 1 :y 2 :width 3 :height 4})
        data    (assoc-in data [:pages-index page-id :objects] objects)
        marks   (brc/usage-marks data red page-id)]
    (t/is (= [{:label "Price" :status :usage :rect {:x 1 :y 2 :width 3 :height 4}}]
             (map #(dissoc % :key) marks)))
    (t/is (= [] (brc/usage-marks data red (uuid/next))) "only on the page in sight")))

;; --- Conflicts: how far along they are

(t/deftest conflicts-wait-until-every-property-has-a-choice
  (let [other (assoc card :id :other)
        card  (assoc card :id :card)
        res   {:card (brc/choose-side card :main)}
        {:keys [pending decided rows]} (brc/conflict-model [card other] res)]
    (t/is (= [:other] (map :id pending)))
    (t/is (= [:card] (map :id decided)))
    (t/is (= [:other :card] (map :id rows)) "the ones that wait come first")))

(t/deftest only-conflicts-without-a-choice-get-prefilled
  (let [card (assoc card :id :card)
        seed (brc/prefill-resolutions [card (assoc card :id :chosen)] {:chosen :main})]
    (t/is (= {:card {:width :branch :opacity :main}} seed))))

(t/deftest the-property-in-turn-is-the-first-without-a-choice
  (let [both (assoc card
                    :base {:rx 8 :width 320 :opacity 1 :fills :a}
                    :main (assoc (:main card) :fills :b)
                    :branch (assoc (:branch card) :fills :c)
                    :changed-attrs (assoc (:changed-attrs card) :fills {:main :b :branch :c}))
        [first-attr second-attr] (brc/conflicting-attrs (brc/conflict-attr-rows both))]
    (t/is (= first-attr (brc/pending-attr both nil nil)))
    (t/is (= second-attr (brc/pending-attr both nil second-attr)) "the one the user took")
    (t/is (= second-attr (brc/pending-attr both {first-attr :main} nil)))
    (t/is (nil? (brc/pending-attr (assoc card :main nil) nil nil))
          "a conflict that does not split by property has none")))
