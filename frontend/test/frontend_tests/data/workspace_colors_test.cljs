;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-colors-test
  (:require
   [app.common.types.color :as cc]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.colors :as dwc]
   [app.main.ui.workspace.colorpicker.hsl :as hsl]
   [clojure.test :as t]
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
