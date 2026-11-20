;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.token-dimension-application-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.tokens.application :as dwta]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(def ^:private tokens
  [(ctob/make-token :name "dimension.md" :type :dimensions :value "24")
   (ctob/make-token :name "dimension.negative" :type :dimensions :value "-8")
   (ctob/make-token :name "spacing.md" :type :spacing :value "16")
   (ctob/make-token :name "sizing.lg" :type :sizing :value "120")])

(defn- setup-file
  "Three rectangles and a flex board with a rectangle inside, with
  `tokens` in an active set."
  []
  (-> (ctht/sample-file-with-tokens
       :lib-fn (fn [lib]
                 (reduce #(ctob/add-token %1 (cthi/id :set) %2)
                         (ctob/add-set lib (ctob/make-token-set :id (cthi/new-id! :set)
                                                                :name "Base"))
                         tokens))
       :status-fn #(ctos/set-tokens-status % #{} #{(cthi/id :set)}))
      (ctho/add-rect :rect)
      (ctho/add-rect :rect-2)
      (ctho/add-rect :rect-3)
      (ctho/add-frame-with-child :layout :layout-child
                                 {:frame-params {:layout :flex}})))

(defn- apply-token
  [file token-name shape-label attributes on-update-shape]
  (dwta/apply-token {:shape-ids [(cthi/id shape-label)]
                     :attributes attributes
                     :token (toht/get-token file token-name)
                     :on-update-shape on-update-shape}))

(defn- run-events
  "Runs `events` on a workspace showing `file`, then calls `check` with
  the file before and after."
  [file events check done]
  (tohs/run-store-async
   (ths/setup-store file) done events
   (fn [state]
     (check file (ths/get-file-from-state state)))))

(t/deftest dimensions-token-on-shape
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "dimension.md" :rect #{:x :y} dwta/apply-dimensions-token)
        (apply-token file "dimension.md" :rect #{:r1} dwta/apply-dimensions-token)
        (apply-token file "dimension.md" :rect-2 #{:stroke-width-left} dwta/apply-dimensions-token)
        (apply-token file "dimension.md" :rect-3 #{:r1 :r2 :r3 :r4} dwta/apply-dimensions-token)]
       (fn [file file']
         (let [rect    (cths/get-shape file :rect)
               rect'   (cths/get-shape file' :rect)
               rect-2' (cths/get-shape file' :rect-2)
               rect-3' (cths/get-shape file' :rect-3)]
           (t/testing "moves the shape"
             (t/is (= 24 (:x rect')))
             (t/is (= 24 (:y rect'))))

           (t/testing "rounds the chosen corners only"
             (t/is (= {:x "dimension.md" :y "dimension.md" :r1 "dimension.md"}
                      (:applied-tokens rect')))
             (t/is (= 24 (:r1 rect')))
             (t/is (= (:r2 rect) (:r2 rect')))
             (t/is (= [24 24 24 24] ((juxt :r1 :r2 :r3 :r4) rect-3'))))

           (t/testing "a shape without stroke gets one, wide on the chosen side only"
             (t/is (= {:stroke-width-left "dimension.md"} (:applied-tokens rect-2')))
             (t/is (= {:stroke-width-top 0
                       :stroke-width-right 0
                       :stroke-width-bottom 0
                       :stroke-width-left 24}
                      (-> (get-in rect-2' [:strokes 0])
                          (select-keys [:stroke-width-top :stroke-width-right
                                        :stroke-width-bottom :stroke-width-left])))))))
       done))))

(t/deftest dimensions-token-on-layout
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "dimension.md" :layout #{:p1 :p3} dwta/apply-dimensions-token)
        (apply-token file "dimension.md" :layout #{:row-gap} dwta/apply-dimensions-token)]
       (fn [file file']
         (let [layout  (cths/get-shape file :layout)
               layout' (cths/get-shape file' :layout)]
           (t/is (= {:p1 "dimension.md" :p3 "dimension.md" :row-gap "dimension.md"}
                    (:applied-tokens layout')))

           (t/testing "sets the chosen paddings only"
             (t/is (= 24 (get-in layout' [:layout-padding :p1])))
             (t/is (= 24 (get-in layout' [:layout-padding :p3])))
             (t/is (= (get-in layout [:layout-padding :p2])
                      (get-in layout' [:layout-padding :p2]))))

           (t/testing "sets the chosen gap only"
             (t/is (= 24 (get-in layout' [:layout-gap :row-gap])))
             (t/is (= (get-in layout [:layout-gap :column-gap])
                      (get-in layout' [:layout-gap :column-gap]))))))
       done))))

(t/deftest dimensions-token-on-layout-child
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "dimension.md" :layout-child #{:m1 :m2 :m3 :m4} dwta/apply-dimensions-token)
        (apply-token file "dimension.md" :layout-child #{:layout-item-min-w :layout-item-max-h}
                     dwta/apply-dimensions-token)]
       (fn [_ file']
         (let [child' (cths/get-shape file' :layout-child)]
           (t/testing "sets the margins"
             (t/is (= {:m1 24 :m2 24 :m3 24 :m4 24} (:layout-item-margin child'))))

           (t/testing "sets the chosen size limits only"
             (t/is (= 24 (:layout-item-min-w child')))
             (t/is (= 24 (:layout-item-max-h child')))
             (t/is (nil? (:layout-item-max-w child')))
             (t/is (nil? (:layout-item-min-h child'))))))
       done))))

(t/deftest negative-dimensions-token-on-size-limits
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "dimension.negative" :layout-child #{:layout-item-min-w}
                     dwta/apply-dimensions-token)]
       (fn [_ file']
         (t/is (= 0 (:layout-item-min-w (cths/get-shape file' :layout-child)))))
       done))))

(t/deftest spacing-token-on-layout
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "spacing.md" :layout #{:p1 :p2 :p3 :p4} dwta/apply-spacing-token)
        (apply-token file "spacing.md" :layout-child #{:m2 :m4} dwta/apply-spacing-token)
        (dwta/apply-spacing-token 16 [(cthi/id :rect)] #{:width})]
       (fn [file file']
         (t/testing "sets the paddings of the board"
           (t/is (= {:p1 16 :p2 16 :p3 16 :p4 16}
                    (:layout-padding (cths/get-shape file' :layout)))))

         (t/testing "sets the chosen margins of the child"
           (let [margin  (:layout-item-margin (cths/get-shape file :layout-child))
                 margin' (:layout-item-margin (cths/get-shape file' :layout-child))]
             (t/is (= 16 (:m2 margin')))
             (t/is (= 16 (:m4 margin')))
             (t/is (= (:m1 margin) (:m1 margin')))))

         (t/testing "never resizes a shape"
           (t/is (= (:width (cths/get-shape file :rect))
                    (:width (cths/get-shape file' :rect))))))
       done))))

(t/deftest spacing-token-from-pill
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(dwta/apply-spacing-token-separated
         {:token  (toht/get-token file "spacing.md")
          :shapes [(cths/get-shape file :layout) (cths/get-shape file :layout-child)]})]
       (fn [_ file']
         (let [layout' (cths/get-shape file' :layout)
               child'  (cths/get-shape file' :layout-child)]
           (t/testing "the board takes the token on its gaps"
             (t/is (= {:row-gap "spacing.md" :column-gap "spacing.md"}
                      (:applied-tokens layout')))
             (t/is (= {:row-gap 16 :column-gap 16} (:layout-gap layout'))))

           (t/testing "the shape inside it takes the token on its margins"
             (t/is (= {:m1 "spacing.md" :m2 "spacing.md" :m3 "spacing.md" :m4 "spacing.md"}
                      (:applied-tokens child')))
             (t/is (= {:m1 16 :m2 16 :m3 16 :m4 16} (:layout-item-margin child'))))))
       done))))

(t/deftest sizing-token
  (t/async
    done
    (let [file (setup-file)]
      (run-events
       file
       [(apply-token file "sizing.lg" :rect-2 #{:width} dwta/apply-sizing-token)
        (dwta/apply-sizing-token 120 [(cthi/id :rect-3)] #{:x})]
       (fn [file file']
         (let [rect-2  (cths/get-shape file :rect-2)
               rect-2' (cths/get-shape file' :rect-2)
               rect-3  (cths/get-shape file :rect-3)
               rect-3' (cths/get-shape file' :rect-3)]
           (t/testing "on the width only, the height stays"
             (t/is (= 120 (:width rect-2')))
             (t/is (= (:height rect-2) (:height rect-2'))))

           (t/testing "never moves a shape"
             (t/is (= (:x rect-3) (:x rect-3'))))))
       done))))
