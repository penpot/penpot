;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.video-test
  (:require
   [app.common.geom.rect :as grc]
   [app.render-wasm.api.video :as video]
   [cljs.test :as t :include-macros true]))

(t/deftest video-inside-the-viewport-is-visible
  (let [vbox (grc/make-rect 0 0 800 600)
        selrect (grc/make-rect 100 100 200 200)]
    (t/is (true? (boolean (video/visible-in-viewport? vbox selrect))))))

(t/deftest video-outside-the-viewport-is-not-visible
  (let [vbox (grc/make-rect 0 0 800 600)
        selrect (grc/make-rect 2000 2000 200 200)]
    (t/is (false? (boolean (video/visible-in-viewport? vbox selrect))))))

(t/deftest video-partly-inside-the-viewport-is-visible
  (let [vbox (grc/make-rect 0 0 800 600)
        selrect (grc/make-rect 700 500 400 400)]
    (t/is (true? (boolean (video/visible-in-viewport? vbox selrect))))))

(t/deftest an-unknown-viewport-counts-as-visible
  (let [selrect (grc/make-rect 2000 2000 200 200)]
    (t/is (true? (boolean (video/visible-in-viewport? nil selrect))))))

(t/deftest unknown-bounds-count-as-visible
  (let [vbox (grc/make-rect 0 0 800 600)]
    (t/is (true? (boolean (video/visible-in-viewport? vbox nil))))))

(t/deftest every-ineligible-code-maps-to-a-reason
  ;; The codes are the `VideoIneligible` discriminants in
  ;; render-wasm/src/render/video.rs; a gap would silently show no explanation.
  (t/is (= (set (range 1 8))
           (set (keys video/ineligible-reasons)))))
