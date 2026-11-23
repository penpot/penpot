;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-colors-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.colors :as dwc]
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
  (let [state  {:colorpicker gradient-colorpicker}
        event  (dwc/update-colorpicker {:color "#000000" :opacity 1})
        result (:colorpicker (ptk/update event state))]
    (t/is (= :color (:type result)))
    (t/is (nil? (:stops result)))
    (t/is (nil? (:gradient result)))))

(t/deftest update-colorpicker-keeps-image-tab-on-image-color
  (let [state  {:colorpicker gradient-colorpicker}
        image  {:id uuid/zero :width 10 :height 10 :mtype "image/png"}
        event  (dwc/update-colorpicker {:image image :opacity 1})
        result (:colorpicker (ptk/update event state))]
    (t/is (= :image (:type result)))))

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
