;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.fonts-test
  (:require
   [app.main.fonts :as fonts]
   [app.util.globals :as globals]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def sample-font
  {:id "sourcesanspro"
   :name "Source Sans Pro"
   :family "sourcesanspro"
   :variants
   [{:id "200"
     :name "200"
     :weight "200"
     :style "normal"
     :suffix "extralight"
     :ttf-url "sourcesanspro-extralight.ttf"}
    {:id "200italic"
     :name "200 Italic"
     :weight "200"
     :style "italic"
     :suffix "extralightitalic"
     :ttf-url "sourcesanspro-extralightitalic.ttf"}
    {:id "300"
     :name "300"
     :weight "300"
     :style "normal"
     :suffix "light"
     :ttf-url "sourcesanspro-light.ttf"}
    {:id "300italic"
     :name "300 Italic"
     :weight "300"
     :style "italic"
     :suffix "lightitalic"
     :ttf-url "sourcesanspro-lightitalic.ttf"}
    {:id "regular"
     :name "400"
     :weight "400"
     :style "normal"
     :ttf-url "sourcesanspro-regular.ttf"}
    {:id "italic"
     :name "400 Italic"
     :weight "400"
     :style "italic"
     :ttf-url "sourcesanspro-italic.ttf"}
    {:id "bold"
     :name "700"
     :weight "700"
     :style "normal"
     :ttf-url "sourcesanspro-bold.ttf"}
    {:id "bolditalic"
     :name "700 Italic"
     :weight "700"
     :style "italic"
     :ttf-url "sourcesanspro-bolditalic.ttf"}
    {:id "black"
     :name "900"
     :weight "900"
     :style "normal"
     :ttf-url "sourcesanspro-black.ttf"}
    {:id "blackitalic"
     :name "900 Italic"
     :weight "900"
     :style "italic"
     :ttf-url "sourcesanspro-blackitalic.ttf"}]
   :backend :builtin})

(t/deftest find-closest-weight-variant-test
  (t/testing "finds exact weight match"
    (let [result (fonts/find-closest-variant sample-font "400" nil)]
      (t/is (= "400" (:weight result)))
      (t/is (= "normal" (:style result)))))

  (t/testing "finds exact weight match with style"
    (let [result (fonts/find-closest-variant sample-font "400" "italic")]
      (t/is (= "400" (:weight result)))
      (t/is (= "italic" (:style result)))))

  (t/testing "chooses higher weight when exactly between two weights"
    (let [result (fonts/find-closest-variant sample-font "350" nil)]
      (t/is (= "400" (:weight result)))))

  (t/testing "finds exact weight match with style"
    (let [result (fonts/find-closest-variant sample-font "350" "italic")]
      (t/is (= "400" (:weight result)))
      (t/is (= "italic" (:style result)))))

  (t/testing "finds closest weight below minimum available"
    (let [result (fonts/find-closest-variant sample-font "0" nil)]
      (t/is (= "200" (:weight result)))))

  (t/testing "finds closest weight above maximum available"
    (let [result (fonts/find-closest-variant sample-font "1000" nil)]
      (t/is (= "900" (:weight result)))))

  (t/testing "keeps the closest weight match when style is not found"
    (let [font {:id "sourcesanspro"
                :name "Source Sans Pro"
                :family "sourcesanspro"
                :variants
                [{:id "200italic"
                  :name "200 Italic"
                  :weight "200"
                  :style "italic"
                  :suffix "extralightitalic"
                  :ttf-url "sourcesanspro-extralightitalic.ttf"}
                 {:id "300"
                  :name "300"
                  :weight "300"
                  :style "normal"
                  :suffix "light"
                  :ttf-url "sourcesanspro-light.ttf"}
                 {:id "300italic"
                  :name "300 Italic"
                  :weight "300"
                  :style "italic"
                  :suffix "lightitalic"
                  :ttf-url "sourcesanspro-lightitalic.ttf"}]}
          result (fonts/find-closest-variant font "200" nil)]
      (t/is (= "200" (:weight result)))
      (t/is (= "italic" (:style result))))))

;; --- preview sprite ----------------------------------------------------------
;;
;; The sprite feature (FLAG :font-preview) caches a pre-parsed SVG node shared by
;; every open font dropdown. `:refs` counts the open dropdowns so the node is only
;; detached when the last one closes. The unit test runner has no browser DOM, so
;; the environment boundary (`globals/browser?`) is mocked and DOM nodes are
;; replaced with minimal fakes exposing only what attach/detach touches.

(t/use-fixtures
  :each
  (fn [test-fn]
    (reset! fonts/preview-sprite {:status :idle :ids #{} :node nil :refs 0})
    (test-fn)))

(defn- fake-node
  "A minimal DOM-like node exposing only what the sprite attach/detach touches."
  []
  #js {:remove (fn [] nil)})

(t/deftest attach-preview-sprite-returns-nil-while-sprite-is-not-ready
  (mock/with-mocks
    {globals/browser? (mock/stub (constantly true))}
    (fn [done]
      (reset! fonts/preview-sprite {:status :loading :ids #{} :node nil :refs 0})
      (t/is (nil? (fonts/attach-preview-sprite!)))
      (t/is (= 0 (:refs @fonts/preview-sprite)))

      (reset! fonts/preview-sprite {:status :error :ids #{} :node nil :refs 0})
      (t/is (nil? (fonts/attach-preview-sprite!)))
      (t/is (= 0 (:refs @fonts/preview-sprite)))
      (done))
    (fn [] nil)))

(t/deftest attach-preview-sprite-increments-refs-and-returns-the-node
  (mock/with-mocks
    {globals/browser? (mock/stub (constantly true))}
    (fn [done]
      (let [node (fake-node)]
        (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node node :refs 0})
        (t/is (identical? node (fonts/attach-preview-sprite!)))
        (t/is (= 1 (:refs @fonts/preview-sprite)))
        (t/is (identical? node (fonts/attach-preview-sprite!)))
        (t/is (= 2 (:refs @fonts/preview-sprite)))
        (done)))
    (fn [] nil)))

(t/deftest detach-preview-sprite-removes-node-only-when-last-reference-drops
  (mock/with-mocks
    {globals/browser? (mock/stub (constantly true))}
    (fn [done]
      (let [removed? (volatile! false)
            node     #js {:remove (fn [] (vreset! removed? true))}]
        (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node node :refs 0})
        (fonts/attach-preview-sprite!)
        (fonts/attach-preview-sprite!)

        ;; First detach keeps the node: another dropdown is still open.
        (fonts/detach-preview-sprite! node)
        (t/is (= 1 (:refs @fonts/preview-sprite)))
        (t/is (false? @removed?))

        ;; Second detach reaches zero refs, so the node is removed from the DOM.
        (fonts/detach-preview-sprite! node)
        (t/is (= 0 (:refs @fonts/preview-sprite)))
        (t/is (true? @removed?))
        (done)))
    (fn [] nil)))

(t/deftest detach-preview-sprite-clamps-refs-at-zero
  (mock/with-mocks
    {globals/browser? (mock/stub (constantly true))}
    (fn [done]
      (let [removed? (volatile! false)
            node     #js {:remove (fn [] (vreset! removed? true))}]
        (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node node :refs 0})
        (fonts/detach-preview-sprite! node)
        (t/is (= 0 (:refs @fonts/preview-sprite)))
        (t/is (true? @removed?))
        (done)))
    (fn [] nil)))

(t/deftest prefetch-preview-sprite-fetches-only-from-idle-or-error
  (let [calls (volatile! 0)
        fetch (mock/stub (fn [& _]
                           (vswap! calls inc)
                           (rx/empty)))]
    (mock/with-mocks
      {globals/browser? (mock/stub (constantly true))
       http/fetch fetch}
      (fn [done]
        ;; :ready → no refetch
        (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node (fake-node) :refs 0})
        (fonts/prefetch-preview-sprite!)
        (t/is (= 0 @calls))

        ;; :loading → no refetch (an earlier request is in flight)
        (reset! fonts/preview-sprite {:status :loading :ids #{} :node nil :refs 0})
        (fonts/prefetch-preview-sprite!)
        (t/is (= 0 @calls))

        ;; :error → retries
        (reset! fonts/preview-sprite {:status :error :ids #{} :node nil :refs 0})
        (fonts/prefetch-preview-sprite!)
        (t/is (= 1 @calls))

        ;; :idle → first fetch
        (reset! fonts/preview-sprite {:status :idle :ids #{} :node nil :refs 0})
        (fonts/prefetch-preview-sprite!)
        (t/is (= 2 @calls))
        (done))
      (fn [] nil))))
