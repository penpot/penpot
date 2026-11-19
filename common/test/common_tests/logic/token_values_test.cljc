;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.token-values-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logic.shapes :as cls]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.transit :as transit]
   [app.common.types.text :as txt]
   [app.common.types.token-values :as cttv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- resolved
  [values]
  (into {} (map (fn [[name value]] [name {:name name :resolved-value value}])) values))

(defn- apply-tokens
  [file label applied]
  (ths/update-shape file label :applied-tokens applied))

(defn- propagation-changes
  ([file values]
   (let [page (thf/current-page file)]
     (propagation-changes file values (tht/applied-targets (:objects page)))))
  ([file values targets]
   (let [page (thf/current-page file)]
     (-> (pcb/empty-changes nil (:id page))
         (pcb/with-page page)
         (cltp/generate-token-propagation (:objects page) (resolved values) targets {})))))

(defn- propagate
  [file values & [targets]]
  (thf/apply-changes file (if targets
                            (propagation-changes file values targets)
                            (propagation-changes file values))))

(defn- update-shapes
  "Apply an update of the shapes `labels` with `update-fn`, keeping their
  tokens like the writes that aren't edits of the user."
  [file labels update-fn & {:as opts}]
  (let [page (thf/current-page file)]
    (thf/apply-changes file
                       (-> (pcb/empty-changes nil (:id page))
                           (pcb/with-objects (:objects page))
                           (pcb/update-shapes (mapv thi/id labels) update-fn
                                              (merge {:ignore-touched true} opts))))))

(defn- token-values
  [file label]
  (:token-values (ths/get-shape file label)))

(t/deftest propagation-records-values
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1 :r1 12)
                  (apply-tokens :rect1 {:fill "red" :r1 "radius" :opacity "missing"}))
        file' (propagate file {"red" "#FF0000" "radius" 12})]
    (t/is (= {:fill "#FF0000" :r1 12} (token-values file' :rect1))
          "also the values the shape already held, not the tokens that don't resolve")

    (t/testing "writing the values twice changes nothing"
      (t/is (empty? (:redo-changes (propagation-changes file' {"red" "#FF0000" "radius" 12})))))))

(t/deftest legacy-shapes-are-unassigned
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:r1 "radius"}))
        file' (update-shapes file [:rect1] #(assoc % :r1 30))
        rect  (ths/get-shape file' :rect1)]
    (t/is (not (cttv/assigned? rect)))
    (t/is (empty? (tht/stale-attrs rect)))
    (t/testing "out of date for editors only"
      (t/is (empty? (cttv/out-of-date-attrs rect (resolved {"radius" 30}) {})))
      (t/is (= #{:r1} (cttv/out-of-date-attrs rect (resolved {"radius" 30}) {:unassigned? true}))))))

(t/deftest writes-to-token-attributes
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-rect :rect1)
                 (apply-tokens :rect1 {:r1 "radius" :opacity "half"})
                 (propagate {"radius" 12 "half" 0.5}))]

    (t/testing "a write marks the attribute stale"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc % :r1 30)) :rect1)]
        (t/is (= #{:r1} (tht/stale-attrs rect)))
        (t/is (= {:opacity 0.5} (:token-values rect)))))

    (t/testing "a derived write keeps the value"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc % :r1 30) :derived? true) :rect1)]
        (t/is (empty? (tht/stale-attrs rect)))))

    (t/testing "a derived write still drops the values of tokens no longer applied"
      (let [file (ths/update-shape file :rect1 :token-values {:r1 12 :opacity 0.5 :r2 4})
            rect (ths/get-shape (update-shapes file [:rect1] #(assoc % :x 30) :derived? true) :rect1)]
        (t/is (= {:r1 12 :opacity 0.5} (:token-values rect)))))

    (t/testing "a write that sets the value too keeps it"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(-> (assoc % :r1 30)
                                                                  (cttv/set-values {:r1 30})))
                                :rect1)]
        (t/is (= 30 (get-in rect [:token-values :r1])))))

    (t/testing "an edit of the user removes the token and its value"
      (let [page  (thf/current-page file)
            file' (thf/apply-changes file
                                     (-> (pcb/empty-changes nil (:id page))
                                         (cls/generate-update-shapes [(thi/id :rect1)]
                                                                     #(assoc % :r1 30)
                                                                     (:objects page)
                                                                     {})))
            rect  (ths/get-shape file' :rect1)]
        (t/is (nil? (get-in rect [:applied-tokens :r1])))
        (t/is (= {:opacity 0.5} (:token-values rect)))))))

(t/deftest applied-tokens-changes
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-rect :rect1)
                 (apply-tokens :rect1 {:r1 "radius" :opacity "half"})
                 (propagate {"radius" 12 "half" 0.5}))]
    (t/testing "a token applied to another attribute is stale"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:applied-tokens :r2] "radius")) :rect1)]
        (t/is (= #{:r2} (tht/stale-attrs rect)))))

    (t/testing "another token for an attribute keeps the value the shape holds"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:applied-tokens :r1] "other")) :rect1)]
        (t/is (= 12 (get-in rect [:token-values :r1])))
        (t/is (= #{:r1} (cttv/out-of-date-attrs rect (resolved {"other" 16}) {})))
        (t/is (empty? (cttv/out-of-date-attrs rect (resolved {"other" 12}) {})))))

    (t/testing "a removed token loses its value"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(update % :applied-tokens dissoc :r1)) :rect1)]
        (t/is (= {:opacity 0.5} (:token-values rect)))))

    (t/testing "without tokens there are no values"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(dissoc % :applied-tokens)) :rect1)]
        (t/is (not (contains? rect :token-values)))))))

(t/deftest only-the-first-fill-and-stroke-count
  (let [fills [{:fill-color "#000000" :fill-opacity 1} {:fill-color "#00FF00" :fill-opacity 1}]
        file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1 :fills fills)
                  (apply-tokens :rect1 {:fill "red" :stroke-width-top "w"})
                  (propagate {"red" "#FF0000" "w" 2}))]
    (t/testing "a later fill"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:fills 1 :fill-color] "#0000FF")) :rect1)]
        (t/is (empty? (tht/stale-attrs rect)))))
    (t/testing "the first fill"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:fills 0 :fill-color] "#0000FF")) :rect1)]
        (t/is (= #{:fill} (tht/stale-attrs rect)))))
    (t/testing "a stroke side without token"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:strokes 0 :stroke-width-left] 5)) :rect1)]
        (t/is (empty? (tht/stale-attrs rect)))))
    (t/testing "the stroke side with a token"
      (let [rect (ths/get-shape (update-shapes file [:rect1] #(assoc-in % [:strokes 0 :stroke-width-top] 5)) :rect1)]
        (t/is (= #{:stroke-width-top} (tht/stale-attrs rect)))))))

(t/deftest typing-keeps-text-values
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-text :text1 "Hello")
                 (apply-tokens :text1 {:font-size "big" :fill "red"})
                 (propagate {"big" 24 "red" "#FF0000"}))]
    (t/testing "a change of the characters"
      (let [text (ths/get-shape (update-shapes file [:text1]
                                               (fn [shape]
                                                 (update shape :content
                                                         #(txt/transform-nodes txt/is-text-node?
                                                                               (fn [node] (assoc node :text "Bye"))
                                                                               %))))
                                :text1)]
        (t/is (empty? (tht/stale-attrs text)))))
    (t/testing "a change of the font size"
      (let [text (ths/get-shape (update-shapes file [:text1]
                                               #(txt/update-text-content % txt/is-text-node?
                                                                         (fn [node _] (assoc node :font-size "8"))
                                                                         nil))
                                :text1)]
        (t/is (= #{:font-size} (tht/stale-attrs text)))))))

(defn- add-boards
  [file]
  (-> file
      (tho/add-frame :frame1 :x 0 :y 0 :width 100 :height 100)
      (tho/add-frame :frame2 :x 200 :y 0 :width 100 :height 100)
      (ths/add-sample-shape :child1 :parent-label :frame1 :x 10 :y 10 :width 10 :height 10)
      (ths/add-sample-shape :child2 :parent-label :frame1 :x 30 :y 10 :width 10 :height 10)
      (apply-tokens :child1 {:m1 "margin" :x "pos" :opacity "half"})
      (propagate {"margin" 4 "pos" 10 "half" 0.5})))

(defn- move-shapes
  [file labels parent-label index]
  (let [page (thf/current-page file)]
    (thf/apply-changes file
                       (-> (pcb/empty-changes nil (:id page))
                           (pcb/with-objects (:objects page))
                           (pcb/change-parent (thi/id parent-label)
                                              (mapv #(ths/get-shape file %) labels)
                                              index)))))

(t/deftest placement-of-the-shapes
  (let [file (-> (thf/sample-file :file1) (add-boards))]
    (t/testing "a move to another parent"
      (let [child (ths/get-shape (move-shapes file [:child1] :frame2 0) :child1)]
        (t/is (= #{:m1 :x} (tht/stale-attrs child)))))

    (t/testing "a move inside the same parent"
      (let [child (ths/get-shape (move-shapes file [:child1] :frame1 1) :child1)]
        (t/is (empty? (tht/stale-attrs child)))))

    (t/testing "a copy of the shape"
      (let [page  (thf/current-page file)
            shape (-> (ths/get-shape file :child1)
                      (assoc :id (thi/new-id! :copy1)))
            file' (thf/apply-changes file
                                     (-> (pcb/empty-changes nil (:id page))
                                         (pcb/with-objects (:objects page))
                                         (pcb/add-object shape)))
            copy  (ths/get-shape file' :copy1)]
        (t/is (= #{:m1 :x} (tht/stale-attrs copy)))
        (t/is (= 0.5 (get-in copy [:token-values :opacity])))))

    (t/testing "a new layout in the parent"
      (let [child (-> (update-shapes file [:frame1] #(assoc % :layout :flex :layout-flex-dir :row))
                      (ths/get-shape :child1))]
        (t/is (= #{:m1} (tht/stale-attrs child)))))))

(t/deftest sizes-held-by-a-layout
  ;; :child1 fills the flex layout of :frame1 on its width, so its width
  ;; token is held, not set
  (let [file (-> (thf/sample-file :file1)
                 (add-boards)
                 (apply-tokens :child1 {:m1 "margin" :x "pos" :opacity "half" :width "size"})
                 (ths/update-shape :child1 :token-values {:m1 4 :x 10 :opacity 0.5 :width 10})
                 (ths/update-shape :child1 :layout-item-h-sizing :fill)
                 (ths/update-shape :frame1 :layout :flex)
                 (ths/update-shape :frame1 :layout-flex-dir :row))]
    (t/is (empty? (tht/stale-attrs (ths/get-shape file :child1))))

    (t/testing "a fixed size again"
      (let [child (-> (update-shapes file [:child1] #(assoc % :layout-item-h-sizing :fix))
                      (ths/get-shape :child1))]
        (t/is (= #{:width} (tht/stale-attrs child)))))

    (t/testing "the parent without layout"
      (let [child (-> (update-shapes file [:frame1] #(dissoc % :layout))
                      (ths/get-shape :child1))]
        (t/is (= #{:m1 :width} (tht/stale-attrs child)))))))

(t/deftest undo-of-a-propagation
  (let [file    (-> (thf/sample-file :file1)
                    (tho/add-rect :rect1)
                    (apply-tokens :rect1 {:fill "color" :r1 "radius"})
                    (propagate {"color" "#FF0000" "radius" 4}))
        changes (propagation-changes file {"color" "#0000FF" "radius" 8})
        file'   (thf/apply-changes file changes)
        file''  (thf/apply-undo-changes file' changes)
        rect    (ths/get-shape file :rect1)
        rect''  (ths/get-shape file'' :rect1)]
    (t/is (= {:fill "#0000FF" :r1 8} (token-values file' :rect1)))
    (t/is (= (:token-values rect) (:token-values rect'')))
    (t/is (empty? (tht/stale-attrs rect'')))
    (t/is (= (:fills rect) (:fills rect'')))
    (t/is (= (:r1 rect) (:r1 rect'')))))

(t/deftest concurrent-values-match-the-shape
  ;; T is red on a fill. A changes T to blue and writes the fill; B, still
  ;; seeing red, applies T to the stroke and writes it.
  (let [file      (-> (thf/sample-file :file1)
                      (tho/add-rect :rect1)
                      (apply-tokens :rect1 {:fill "T"})
                      (propagate {"T" "#ff0000"}))
        id        (thi/id :rect1)
        changes-a (propagation-changes file {"T" "#0000ff"} {id #{:fill}})
        file-b    (apply-tokens file :rect1 {:fill "T" :stroke-color "T"})
        page-b    (thf/current-page file-b)
        changes-b (-> (pcb/empty-changes nil (:id page-b))
                      (pcb/with-objects (:objects (thf/current-page file)))
                      (pcb/update-shapes [id] #(assoc-in % [:applied-tokens :stroke-color] "T")
                                         {:ignore-touched true})
                      (as-> $ (cltp/generate-token-propagation $ (pcb/lookup-objects $)
                                                               (resolved {"T" "#ff0000"})
                                                               {id #{:stroke-color}} {})))
        check     (fn [file]
                    (let [rect (ths/get-shape file :rect1)]
                      (t/is (= (get-in rect [:fills 0 :fill-color]) (get-in rect [:token-values :fill])))
                      (t/is (= (get-in rect [:strokes 0 :stroke-color])
                               (get-in rect [:token-values :stroke-color])))))]
    (t/testing "A then B"
      (check (-> file (thf/apply-changes changes-a) (thf/apply-changes changes-b))))
    (t/testing "B then A"
      (check (-> file (thf/apply-changes changes-b) (thf/apply-changes changes-a))))))

(t/deftest component-sync-copies-values
  (let [file   (-> (thf/sample-file :file1)
                   (tho/add-simple-component-with-copy :component1 :main1-root :main1-child :copy1-root)
                   (apply-tokens :main1-child {:fill "T"})
                   (propagate {"T" "#FF0000"})
                   (tho/propagate-component-changes :component1)
                   (propagate {"T" "#00FF00"} {(thi/id :main1-child) #{:fill}})
                   (tho/propagate-component-changes :component1))
        copy   (ths/get-shape-by-id file (-> (ths/get-shape file :copy1-root) :shapes first))]
    (t/is (= "T" (get-in copy [:applied-tokens :fill])))
    (t/is (= "#00ff00" (get-in copy [:fills 0 :fill-color])))
    (t/is (= "#00FF00" (get-in copy [:token-values :fill])))))

(t/deftest validation-and-repair
  (let [file   (-> (thf/sample-file :file1)
                   (tho/add-rect :rect1)
                   (apply-tokens :rect1 {:r1 "radius"})
                   (ths/update-shape :rect1 :token-values {:r1 4 :r2 4}))
        errors (cfv/validate-file file {})
        file'  (thf/apply-changes file {:redo-changes (cfr/repair-file file {} errors)})]
    (t/is (= [:token-values-mismatch] (map :code errors)))
    (t/is (= {:r1 4} (token-values file' :rect1)))))

(t/deftest round-trip
  (let [file  (-> (thf/sample-file :file1)
                  (tho/add-rect :rect1)
                  (apply-tokens :rect1 {:shadow "drop"})
                  (propagate {"drop" [{:offset-x 1 :offset-y 1 :blur 2 :spread 0
                                       :color "#000000" :inset false}]}))
        shape (ths/get-shape file :rect1)]
    (t/is (= (:token-values shape)
             (-> shape transit/encode-str transit/decode-str :token-values)))))

(t/deftest colors-are-compared-as-colors
  (let [shape    {:applied-tokens {:fill "color" :font-family "family"}
                  :token-values {:fill "#ffffff00" :font-family "red"}}
        out-of-date #(cttv/out-of-date-attrs shape (resolved %) {})]
    (t/is (empty? (out-of-date {"color" "rgba(255, 255, 255, 0)" "family" "red"}))
          "the same color spelled in another way")
    (t/is (empty? (out-of-date {"color" "#FFFFFF00" "family" "red"})))
    (t/is (= #{:fill} (out-of-date {"color" "rgba(255, 255, 255, 0.5)" "family" "red"}))
          "another alpha")
    (t/is (= #{:fill} (out-of-date {"color" "#000000" "family" "red"})))
    (t/is (= #{:font-family} (out-of-date {"color" "#ffffff00" "family" "Red"}))
          "other attributes are compared as they are")))

(t/deftest resolved-values-are-compared-as-colors
  (t/is (cttv/same-resolved-values? {"color" "#c0404080" "weight" "700"}
                                    {"color" "rgba(192, 64, 64, 0.5)" "weight" "700"}))
  (t/is (not (cttv/same-resolved-values? {"color" "#c04040"} {"color" "#c04041"})))
  (t/is (not (cttv/same-resolved-values? {"weight" "700"} {"weight" 700})))
  (t/is (not (cttv/same-resolved-values? {"color" "#c04040"} {"color" "#c04040" "size" 4})))
  (t/is (not (cttv/same-resolved-values? {"color" "#c04040" "size" 4} {"color" "#c04040" "other" 4})))
  (t/is (not (cttv/same-resolved-values? nil {"color" "#c04040"}))))
