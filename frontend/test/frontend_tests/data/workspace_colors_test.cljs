;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-colors-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as cc]
   [app.common.types.fills :as types.fills]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.colors :as dwc]
   [app.main.ui.workspace.colorpicker.hsl :as hsl]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(t/deftest build-change-fill-event
  (let [color1 {:color "#fabada"
                :opacity 1}
        color2 {:color "#fabada"
                :opacity 1
                :ref-id uuid/zero
                :ref-file uuid/zero}
        color3 {:color "#fabada"
                :opacity -1}
        color4 {:opacity 1
                :color "ffffff"}]
    (t/is (ptk/event? (dwc/change-fill #{uuid/zero} color1 1)))
    (t/is (ptk/event? (dwc/change-fill #{uuid/zero} color2 1)))
    (t/is (thrown? js/Error
                   (ptk/event? (dwc/change-fill #{uuid/zero} color3 1))))
    (t/is (thrown? js/Error
                   (ptk/event? (dwc/change-fill #{uuid/zero} color4 1))))))

(t/deftest build-add-fill-event
  (let [color1 {:color "#fabada"
                :opacity 1}
        color2 {:color "#fabada"
                :opacity 1
                :ref-id uuid/zero
                :ref-file uuid/zero}
        color3 {:color "#fabada"
                :opacity -1}
        color4 {:opacity 1
                :color "ffffff"}]
    (t/is (ptk/event? (dwc/add-fill #{uuid/zero} color1 1)))
    (t/is (ptk/event? (dwc/add-fill #{uuid/zero} color2 1)))
    (t/is (thrown? js/Error
                   (ptk/event? (dwc/add-fill #{uuid/zero} color3 1))))
    (t/is (thrown? js/Error
                   (ptk/event? (dwc/add-fill #{uuid/zero} color4 1))))))

(t/deftest update-shape-stroke-attrs-changes-an-existing-stroke
  (let [shape {:strokes [{:stroke-color "#000000" :stroke-opacity 1
                          :stroke-width 1 :stroke-style :solid
                          :stroke-alignment :inner}]}
        result (dwc/update-shape-stroke-attrs shape 0 {:stroke-width 4})]
    (t/is (= {:stroke-color "#000000" :stroke-opacity 1
              :stroke-width 4 :stroke-style :solid
              :stroke-alignment :inner}
             (get-in result [:strokes 0])))))

(t/deftest update-shape-stroke-attrs-creates-a-stroke-with-color
  ;; Regression for https://github.com/penpot/penpot/issues/12463.
  ;; A stroke built from style attributes alone has no color, so the
  ;; backend rejects the shape as invalid.
  (let [result (dwc/update-shape-stroke-attrs {:type :bool} 0 {:stroke-width 4})
        stroke (get-in result [:strokes 0])]
    (t/is (= 1 (count (:strokes result))))
    (t/is (= "#000000" (:stroke-color stroke)))
    (t/is (= 4 (:stroke-width stroke)))
    (t/is (sm/validate cts/schema:stroke stroke))))


(t/deftest update-colorpicker-color-skips-add-recent-on-incomplete-image-state
  ;; Regression for https://github.com/penpot/penpot/issues/8443.
  ;;
  ;; Closing the fill dialog while the image upload is still in flight leaves
  ;; the colorpicker's current-color with only :opacity (no :image, :gradient,
  ;; or :color). Before the guard, ptk/watch eagerly built (add-recent-color
  ;; partial), which calls (clr/check-color partial) and threw an "expected
  ;; valid color" assertion that surfaced as an Internal Assertion Error toast.
  (let [partial-image-state {:colorpicker {:type :image
                                           :current-color {:opacity 1}}}
        event (dwc/update-colorpicker-color {} true)]
    (t/is (nil? (ptk/watch event partial-image-state nil)))))


(t/deftest update-colorpicker-color-skips-add-recent-when-only-opacity-on-color-type
  ;; Same incomplete-state shape, but the colorpicker is on the plain-color tab
  ;; (e.g. the user clicked elsewhere before the picker had a chance to commit
  ;; a hex). The existing :type-and-:color-nil guard sits on the colorpicker
  ;; map's :type — but get-color-from-colorpicker-state strips :type from its
  ;; output, so that guard never fires. The schema-based guard catches it.
  (let [colorless-state {:colorpicker {:type :color
                                       :current-color {:opacity 1}}}
        event (dwc/update-colorpicker-color {} true)]
    (t/is (nil? (ptk/watch event colorless-state nil)))))


(t/deftest update-colorpicker-color-still-emits-recent-for-valid-plain-color
  ;; Sanity check: a fully-populated plain color still produces a watch
  ;; observable (the rx/of branch is reached) so we know the guard isn't
  ;; over-eager and silently dropping legitimate colors.
  (let [valid-state {:colorpicker {:type :color
                                   :current-color {:color "#ff0000" :opacity 1}}}
        event (dwc/update-colorpicker-color {} true)]
    (t/is (some? (ptk/watch event valid-state nil)))))

(t/deftest hsl-boundary-editing
  (let [original (dwc/split-color-components {:color "#0d5af2" :opacity 1})
        expected (hsl/components original)]
    (doseq [lightness [0 1]]
      (let [boundary (hsl/update-color original :hsl-l lightness)
            restored (hsl/update-color boundary :hsl-l 0.5)]
        (t/is (= expected (hsl/components restored)))
        (t/is (= "#0d5af2" (:hex restored)))
        (t/is (every? js/Number.isFinite (map restored [:r :g :b])))))
    (let [gray (hsl/update-color original :hsl-s 0)
          green (hsl/update-color gray :h 120)
          saturated (hsl/update-color green :hsl-s 0.5)]
      (t/is (= [120 0.5 0.5] (:hsl saturated))))))

(t/deftest hsl-draft-stays-in-colorpicker
  (let [color (dwc/materialize-color-components
               (hsl/update-color (dwc/split-color-components
                                  {:color "#0d5af2" :opacity 1}) :hsl-l 0))
        state {:colorpicker {:type :color :current-color color}}
        changed (ptk/update (dwc/update-colorpicker-color
                             {:hex "#ff0000" :r 255 :g 0 :b 0 :h 0 :s 1 :v 255} false)
                            state)]
    (t/is (not (contains? (get-in changed [:colorpicker :current-color]) :hsl)))
    (t/is (not (contains? (dwc/get-color-from-colorpicker-state (:colorpicker state)) :hsl)))
    (let [gradient {:type :linear-gradient :current-color color
                    :editing-stop 0 :stops [(assoc color :offset 0)]
                    :gradient {} :opacity 1}]
      (t/is (not (contains? (get-in (dwc/get-color-from-colorpicker-state gradient)
                                    [:gradient :stops 0]) :hsl))))))

(t/deftest hsl-white-is-finite
  (t/is (= [0 0 1] (cc/rgb->hsl [255 255 255]))))

(t/deftest hsl-gradient-echo-preserves-draft
  (doseq [[property value] [[:hsl-l 0] [:hsl-l 1] [:hsl-s 0]]]
    (let [original (dwc/split-color-components {:color "#0d5af2" :opacity 1})
          color (assoc (dwc/materialize-color-components
                        (merge original (hsl/update-color original property value))) :offset 0)
          state {:colorpicker {:type :linear-gradient :current-color color
                               :editing-stop 0 :stops [color]
                               :gradient {:type :linear :shape-id nil} :opacity 1}}
          data (dwc/get-color-from-colorpicker-state (:colorpicker state))
          echoed (ptk/update (dwc/update-colorpicker data) state)
          changed (ptk/update (dwc/update-colorpicker-color {:hex "#ff0000"} false) state)]
      (t/is (= (:hsl color) (get-in echoed [:colorpicker :current-color :hsl])))
      (when (= property :hsl-s)
        (t/is (= "#406bbf" (:hex (hsl/update-color
                                  (get-in echoed [:colorpicker :current-color]) :hsl-s 0.5)))))
      (t/is (not (contains? (get-in changed [:colorpicker :stops 0]) :hsl))))))

(t/deftest ^:async color-change-updates-the-shapes-of-a-property-in-one-event
  ;; Changing a color in a selection of many shapes must not commit once per
  ;; shape: each commit makes the page objects a new map, and everything that
  ;; derives from them runs again.
  (let [[a b c d e] (repeatedly 5 uuid/next)
        color      {:color "#ff0000" :opacity 1}
        old-color  {:color "#000000" :opacity 1}
        operations [{:prop :fill :shape-id a :index 0}
                    {:prop :fill :shape-id b :index 0}
                    {:prop :fill :shape-id c :index 1}
                    {:prop :stroke :shape-id a :index 0}
                    {:prop :stroke :shape-id d :index 0}
                    {:prop :stroke :shape-id e :index 0}]
        types      (atom [])]
    (await (async/observe (ptk/watch (dwc/change-color-in-selected operations color old-color) {} nil)
                          :on-next #(swap! types conj (ptk/type %))))

    (t/is (= {:app.main.data.workspace.undo/start-undo-transaction  1
              :app.main.data.workspace.colors/change-fill           2
              :app.main.data.workspace.colors/change-stroke-color   1
              :app.main.data.workspace.undo/commit-undo-transaction 1}
             (frequencies @types)))))

(def ^:private gradient-colorpicker
  {:type :linear-gradient
   :editing-stop 0
   :gradient {:type :linear :start-x 0.5 :start-y 0 :end-x 0.5 :end-y 1 :width 1}
   :stops [{:color "#000000" :opacity 1 :offset 0}
           {:color "#000000" :opacity 0 :offset 1}]
   :current-color {:color "#000000" :opacity 1 :offset 0}})

(t/deftest update-colorpicker-leaves-gradient-tab-on-plain-color
  ;; Regression for https://github.com/penpot/penpot/issues/12463.
  ;; Undoing a gradient with the colorpicker open sends a plain color.
  ;; The colorpicker dropped the stops but stayed in gradient mode, so
  ;; adding a stop built a gradient without coordinates or colors.
  (doseq [type [:linear-gradient :radial-gradient]]
    (t/testing (name type)
      (let [state  {:colorpicker (assoc gradient-colorpicker :type type)}
            event  (dwc/update-colorpicker {:color "#000000" :opacity 1})
            result (:colorpicker (ptk/update event state))]
        (t/is (= :color (:type result)))
        (t/is (nil? (:stops result)))
        (t/is (nil? (:gradient result)))))))

(t/deftest update-colorpicker-switches-to-image-tab-on-image-color
  (let [state  {:colorpicker gradient-colorpicker}
        image  {:id uuid/zero :width 10 :height 10 :mtype "image/png"}
        event  (dwc/update-colorpicker {:image image :opacity 1})
        result (:colorpicker (ptk/update event state))]
    (t/is (= :image (:type result)))))

(t/deftest update-colorpicker-keeps-color-tab-on-plain-color
  (let [state  {:colorpicker {:type :color
                              :current-color {:color "#000000" :opacity 1}}}
        event  (dwc/update-colorpicker {:color "#ff0000" :opacity 1})
        result (:colorpicker (ptk/update event state))]
    (t/is (= :color (:type result)))
    (t/is (= "#ff0000" (get-in result [:current-color :color])))))

(t/deftest colorpicker-onchange-runner-skips-invalid-gradient
  (let [calls  (atom [])
        runner (#'dwc/colorpicker-onchange-runner #(swap! calls conj %))
        empty-gradient {:colorpicker {:type :linear-gradient
                                      :stops [{:offset 0.5}]}}]
    (ptk/watch runner empty-gradient nil)
    (t/is (empty? @calls))
    (ptk/watch runner {:colorpicker gradient-colorpicker} nil)
    (t/is (= 1 (count @calls)))
    (t/is (= [{:color "#000000" :opacity 1 :offset 0}
              {:color "#000000" :opacity 0 :offset 1}]
             (get-in (first @calls) [:gradient :stops])))))

(t/deftest colorpicker-onchange-runner-applies-plain-and-image-colors
  ;; The guard only drops invalid gradients: colors without a gradient
  ;; must still reach `on-change`.
  (let [calls  (atom [])
        runner (#'dwc/colorpicker-onchange-runner #(swap! calls conj %))
        image  {:id uuid/zero :width 10 :height 10 :mtype "image/png"}]
    (ptk/watch runner {:colorpicker {:type :color
                                     :current-color {:color "#ff0000" :opacity 1}}} nil)
    (ptk/watch runner {:colorpicker {:type :image
                                     :current-color {:image image :opacity 1}}} nil)
    (t/is (= 2 (count @calls)))
    (t/is (= "#ff0000" (:color (first @calls))))
    (t/is (= image (:image (second @calls))))))

(def ^:private text-image
  {:id #uuid "00000000-0000-0000-0000-000000000001"
   :width 100
   :height 100
   :mtype "image/png"})

(t/deftest change-text-color-sets-an-image
  ;; Regression for https://github.com/penpot/penpot/issues/12463.
  ;; Picking an image in Selected colors for a text left a fill with only
  ;; :fill-opacity, which the backend rejects as an invalid shape.
  (let [node   {:text "Night" :fills [{:fill-color "#000000" :fill-opacity 1}]}
        result (#'dwc/change-text-color {:color "#000000" :opacity 1}
                                        {:image text-image :opacity 0.9}
                                        0
                                        node)]
    (t/is (= [{:fill-image text-image :fill-opacity 0.9}] (:fills result)))
    (t/is (sm/validate types.fills/schema:fill (first (:fills result))))))

(t/deftest change-text-color-replaces-an-image
  (let [node   {:text "Night" :fills [{:fill-image text-image :fill-opacity 1}]}
        result (#'dwc/change-text-color {:image text-image :opacity 1}
                                        {:color "#fabada" :opacity 1}
                                        0
                                        node)]
    (t/is (= [{:fill-color "#fabada" :fill-opacity 1}] (:fills result)))))
