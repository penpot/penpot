;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.benches.geometry-scenes-test
  (:require
   [app.common.types.path :as path]
   [app.common.uuid :as uuid]
   [benches.render-wasm.cases :as cases]
   [benches.render-wasm.scenes.common :as common]
   [benches.render-wasm.scenes.masks :as masks]
   [benches.render-wasm.scenes.paths :as paths]
   [benches.render-wasm.scenes.plus :as plus]
   [cljs.test :as t :include-macros true]
   [frontend-tests.benches.test-helpers :as helpers]))

(t/deftest curved-paths-use-canonical-content-and-stable-order
  (let [params   {:count 12 :width 1920 :height 1080 :seed 42}
        scene    (paths/build params)
        again    (paths/build params)
        shapes   (helpers/children-of scene uuid/zero)
        first    (first shapes)
        content  (vec (:content first))]
    (t/is (= 12 (count shapes)))
    (t/is (= scene again) "same scene parameters and seed reproduce content and ids")
    (t/is (path/content? (:content first)))
    (t/is (= [:move-to :curve-to :line-to :close-path]
             (mapv :command content)))
    (t/is (pos? (:width (:selrect first))))
    (t/is (pos? (:height (:selrect first))))
    (t/is (= (mapv :id shapes) (mapv :id (rest (common/upload-order scene)))))
    (t/is (= scene (common/validate! scene)))))

(t/deftest plus-paths-have-two-crossing-open-lines
  (let [scene    (plus/build {:count 8 :width 1920 :height 1080 :seed 42})
        first    (first (helpers/children-of scene uuid/zero))
        segments (vec (:content first))
        [a b c d] segments]
    (t/is (= 8 (count (helpers/children-of scene uuid/zero))))
    (t/is (= [:move-to :line-to :move-to :line-to]
             (mapv :command segments)))
    (t/is (= (get-in a [:params :y]) (get-in b [:params :y]))
          "the first line is horizontal")
    (t/is (< (get-in a [:params :x]) (get-in b [:params :x])))
    (t/is (= (get-in c [:params :x]) (get-in d [:params :x]))
          "the second line is vertical")
    (t/is (< (get-in c [:params :y]) (get-in d [:params :y])))
    (t/is (seq (:strokes first)))
    (t/is (= scene (common/validate! scene)))))

(t/deftest mask-child-order-and-descendants-are-reachable
  (let [scene     (masks/build {:width 1920 :height 1080 :seed 42})
        groups    (helpers/children-of scene uuid/zero)
        group     (first groups)
        children  (helpers/children-of scene (:id group))
        mask      (first children)
        uploaded  (common/upload-order scene)]
    (t/is (= 24 (count groups)))
    (t/is (= 241 (count (:objects scene))) "root, groups and their nine children")
    (t/is (= (:id group) (get-in scene [:refs [:mask 0]])))
    (t/is (true? (:masked-group group)))
    (t/is (= :circle (:type mask)) "the first child is the mask")
    (t/is (= 9 (count children)))
    (t/is (every? #(= (:id group) (:parent-id %)) children))
    (t/is (= (:selrect mask) (:selrect group)))
    (t/is (= uuid/zero (:id (first uploaded))))
    (t/is (= (:id group) (:id (second uploaded))))
    (t/is (= (:id mask) (:id (nth uploaded 2))))
    (t/is (= (count (:objects scene)) (count uploaded)))
    (t/is (= scene (common/validate! scene)))))

(t/deftest camera-cases-share-each-scene-seed
  (doseq [scene-id [:paths :plus :masks]]
    (let [selected (->> (cases/collect-cases {:master-seed 42})
                        (filter #(= scene-id (:scene %)))
                        vec)]
      (t/is (= #{"load" "pan" "zoom"} (set (map (comp name :id) selected))))
      (t/is (= 1 (count (set (map :scene-seed selected)))))
      (t/is (every? #(not (contains? % :run!)) selected)))))
