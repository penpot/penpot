;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.path-actions-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.path :as path]
   [app.main.data.workspace.path.helpers :as path.helpers]
   [app.main.ui.workspace.viewport.path-actions :as path.actions]
   [cljs.test :as t :include-macros true]
   [frontend-tests.logic.path-test-helpers :as pth]))

(t/deftest mixed-corner-and-curve-selection-enables-both-conversions
  (let [content (pth/mixed-corner-curve-content)
        points  (path/get-points content)
        enabled (path.helpers/check-enabled content #{0 1})]
    (t/is (false? (path/is-curve-point? content (first points))))
    (t/is (true? (path/is-curve-point? content (second points))))
    (t/is (true? (:make-corner enabled)))
    (t/is (true? (:make-curve enabled)))))

(t/deftest action-eligibility-keeps-coincident-node-identities
  (let [content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :line-to :params {:x 10 :y 0}}
                  {:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to
                   :params {:c1x 2 :c1y 2 :c2x 8 :c2y 2 :x 10 :y 0}}])
        enabled (path.helpers/check-enabled content #{0 2})]
    (t/is (true? (:make-corner enabled)))
    (t/is (true? (:make-curve enabled)))
    (t/is (true? (:merge-nodes enabled)))
    (t/is (true? (:join-nodes enabled)))))

(t/deftest toolbar-separators-only-render-between-visible-tool-groups
  (t/are [structural? shape? handler? expected]
         (= expected
            (path.actions/toolbar-group-visibility structural? shape? handler?))
    false false false
    {:shape-handler-visible? false
     :node-groups-separator-visible? false
     :snap-separator-visible? false}

    true false false
    {:shape-handler-visible? false
     :node-groups-separator-visible? false
     :snap-separator-visible? true}

    false true false
    {:shape-handler-visible? true
     :node-groups-separator-visible? false
     :snap-separator-visible? true}

    true true false
    {:shape-handler-visible? true
     :node-groups-separator-visible? true
     :snap-separator-visible? true}

    true false true
    {:shape-handler-visible? true
     :node-groups-separator-visible? true
     :snap-separator-visible? true}))

(t/deftest handler-toolbar-represents-equal-and-mixed-multi-node-modes
  (let [content (path/content
                 [{:command :move-to :params {:x 0 :y 0}}
                  {:command :curve-to
                   :params {:c1x 2 :c1y 0 :c2x 8 :c2y 0 :x 10 :y 0}}
                  {:command :curve-to
                   :params {:c1x 12 :c1y 0 :c2x 18 :c2y 0 :x 20 :y 0}}
                  {:command :curve-to
                   :params {:c1x 22 :c1y 0 :c2x 28 :c2y 0 :x 30 :y 0}}])
        nodes   #{1 2}]
    ;; Matching nodes share one active mode.
    (t/is (= {:nodes #{1 2} :active-type :mirror}
             (path.helpers/handler-selection-state content {} nodes)))
    ;; Stored mixed modes return `:mixed`.
    (t/is (= {:nodes #{1 2} :active-type :mixed}
             (path.helpers/handler-selection-state content {2 :aligned} nodes)))
    (t/is (= {:nodes #{1 2} :active-type :aligned}
             (path.helpers/handler-selection-state
              content {1 :aligned 2 :aligned} nodes)))
    (t/is (= :open (path.helpers/handler-trigger-action :mixed)))
    (t/is (= :select (path.helpers/handler-trigger-action :mirror)))))

(t/deftest handler-toolbar-detects-derived-independent-and-mirror-targets
  (let [content         (path/content
                         [{:command :move-to :params {:x 0 :y 0}}
                          {:command :curve-to
                           :params {:c1x 2 :c1y 0 :c2x 8 :c2y 0 :x 10 :y 0}}
                          {:command :curve-to
                           :params {:c1x 12 :c1y 0 :c2x 18 :c2y 5 :x 20 :y 0}}])
        node-targets    #{1 2}
        handler-targets (path.helpers/handler-target-nodes
                         content
                         {:nodes #{}
                          :segments #{}
                          :handlers #{[1 :c2] [2 :c2]}})]
    (t/is (= :mirror (path.helpers/derive-handler-type content 1)))
    (t/is (= :independent (path.helpers/derive-handler-type content 2)))
    (t/is (= {:nodes #{1 2} :active-type :mixed}
             (path.helpers/handler-selection-state content {} node-targets)))
    (t/is (= #{1 2} handler-targets))
    (t/is (= {:nodes #{1 2} :active-type :mixed}
             (path.helpers/handler-selection-state content {} handler-targets)))))

(t/deftest opposite-handler-target-matches-handler-mode
  (let [node     (gpt/point 10 0)
        handler  (gpt/point 14 3)
        opposite (gpt/point 6 0)]
    (t/is (= (gpt/point 6 -3)
             (path.helpers/opposite-handler-target node handler opposite :mirror)))
    (t/is (= 4
             (gpt/distance
              node
              (path.helpers/opposite-handler-target node handler opposite :aligned))))))

