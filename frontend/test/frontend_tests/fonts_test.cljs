;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.fonts-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.fonts :as df]
   [app.main.fonts :as fonts]
   [app.main.store :as st]
   [app.util.globals :as globals]
   [app.util.http :as http]
   [app.util.webapi :as wa]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

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
  {:before #(reset! fonts/preview-sprite {:status :idle :ids #{} :node nil :refs 0})})

(defn- fake-node
  "A minimal DOM-like node exposing only what the sprite attach/detach touches."
  []
  #js {:remove (fn [] nil)})

(t/deftest ^:async attach-preview-sprite-returns-nil-while-sprite-is-not-ready
  (await
   (mock/with-mocks*
     {globals/browser? (mock/stub (constantly true))}
     (reset! fonts/preview-sprite {:status :loading :ids #{} :node nil :refs 0})
     (t/is (nil? (fonts/attach-preview-sprite!)))
     (t/is (= 0 (:refs @fonts/preview-sprite)))

     (reset! fonts/preview-sprite {:status :error :ids #{} :node nil :refs 0})
     (t/is (nil? (fonts/attach-preview-sprite!)))
     (t/is (= 0 (:refs @fonts/preview-sprite)))
     ;; Trailing settle: the body is synchronous, but `with-mocks*`
     ;; evaluates to a promise, so the body must settle one.
     (await (async/settle)))))

(t/deftest ^:async attach-preview-sprite-increments-refs-and-returns-the-node
  (await
   (mock/with-mocks*
     {globals/browser? (mock/stub (constantly true))}
     (let [node (fake-node)]
       (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node node :refs 0})
       (t/is (identical? node (fonts/attach-preview-sprite!)))
       (t/is (= 1 (:refs @fonts/preview-sprite)))
       (t/is (identical? node (fonts/attach-preview-sprite!)))
       (t/is (= 2 (:refs @fonts/preview-sprite)))
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

(t/deftest ^:async detach-preview-sprite-removes-node-only-when-last-reference-drops
  (await
   (mock/with-mocks*
     {globals/browser? (mock/stub (constantly true))}
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
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

(t/deftest ^:async detach-preview-sprite-clamps-refs-at-zero
  (await
   (mock/with-mocks*
     {globals/browser? (mock/stub (constantly true))}
     (let [removed? (volatile! false)
           node     #js {:remove (fn [] (vreset! removed? true))}]
       (reset! fonts/preview-sprite {:status :ready :ids #{"a"} :node node :refs 0})
       (fonts/detach-preview-sprite! node)
       (t/is (= 0 (:refs @fonts/preview-sprite)))
       (t/is (true? @removed?))
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

(t/deftest ^:async prefetch-preview-sprite-fetches-only-from-idle-or-error
  (let [calls (volatile! 0)
        fetch (mock/stub (fn [& _]
                           (vswap! calls inc)
                           (rx/empty)))]
    (await
     (mock/with-mocks*
       {globals/browser? (mock/stub (constantly true))
        http/fetch fetch}
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
       ;; Trailing settle: the body is synchronous, but `with-mocks*`
       ;; evaluates to a promise, so the body must settle one.
       (await (async/settle))))))

;; --- custom font upload ------------------------------------------------------
;;
;; Regression tests for https://github.com/penpot/penpot/issues/12304:
;; uploading a font whose opentype.js names are missing (nil subfamily,
;; as seen with some Fontshare families) crashed `process-upload` with
;; `re-seq must match against a string` and left the user with no
;; feedback. Unknown signatures and unparseable files were silently
;; dropped as well.

(defn- bytes->buffer
  [bytes]
  (.-buffer (js/Uint8Array. (clj->js bytes))))

(defn- fake-ot-font
  [{:keys [family variant hhea os2]}]
  (let [names {"preferredFamily" family
               "fontFamily" family
               "preferredSubfamily" variant
               "fontSubfamily" variant}]
    #js {:getEnglishName (fn [k] (get names k))
         :tables #js {:hhea hhea :os2 os2}}))

(t/deftest parse-font-mtype-detects-font-signatures
  (t/is (= "font/ttf" (#'df/parse-font-mtype (bytes->buffer [0 1 0 0]))))
  (t/is (= "font/otf" (#'df/parse-font-mtype (bytes->buffer [79 84 84 79]))))
  (t/is (= "font/woff" (#'df/parse-font-mtype (bytes->buffer [119 79 70 70]))))
  (t/is (= "font/woff2" (#'df/parse-font-mtype (bytes->buffer [119 79 70 50]))))
  (t/is (nil? (#'df/parse-font-mtype (bytes->buffer [1 2 3 4]))))
  (t/is (nil? (#'df/parse-font-mtype nil)))
  (t/is (nil? (#'df/parse-font-mtype (bytes->buffer [0 1])))))

(t/deftest prepare-font-defaults-missing-subfamily
  (let [font (fake-ot-font {:family "Satoshi"
                            :variant nil
                            :hhea #js {:ascender 1000 :descender 300}
                            :os2 #js {:usWinAscent 1000 :usWinDescent 300
                                      :sTypoAscender 800 :sTypoDescender 200
                                      :fsSelection 0}})
        entry (#'df/prepare-font {:font font
                                  :type "font/otf"
                                  :name "Satoshi-Regular.otf"
                                  :data (bytes->buffer [79 84 84 79 0 0 0 0])})]
    (t/is (= "Satoshi" (:font-family entry)))
    (t/is (= 400 (:font-weight entry)))
    (t/is (= "normal" (:font-style entry)))
    (t/is (false? (:height-warning? entry)))))

(t/deftest prepare-font-tolerates-missing-tables
  (let [font  (fake-ot-font {:family "Satoshi" :variant "Regular" :hhea nil :os2 nil})
        entry (#'df/prepare-font {:font font
                                  :type "font/ttf"
                                  :name "Satoshi-Regular.ttf"
                                  :data (bytes->buffer [0 1 0 0 0 0 0 0])})]
    (t/is (= "Satoshi" (:font-family entry)))
    (t/is (false? (:height-warning? entry)))))

(t/deftest parse-font-returns-error-on-unparseable
  (t/testing "read errors pass through"
    (t/is (= {:error "x.ttf"} (#'df/parse-font {:error "x.ttf"}))))
  (t/testing "woff2 keeps a nil font for filename-based metadata"
    (let [result (#'df/parse-font {:data (bytes->buffer [119 79 70 50])
                                   :type "font/woff2"
                                   :name "Satoshi-Bold.woff2"})]
      (t/is (nil? (:font result)))
      (t/is (nil? (:error result)))))
  (t/testing "opentype.js failures become errors instead of silent drops"
    (let [garbage (bytes->buffer (into [0 1 0 0] (repeat 96 0)))
          result  (#'df/parse-font {:data garbage :type "font/ttf" :name "bad.ttf"})]
      (t/is (= "bad.ttf" (:error result))))))

(t/deftest ^:async process-upload-reports-unknown-signature
  (let [team-id (uuid/next)
        blob    #js {:name "mystery.bin"}
        emitted (atom [])
        result  (atom nil)]
    (await (mock/with-mocks*
             {wa/read-file-as-array-buffer (mock/stub (fn [_] (rx/of (bytes->buffer [1 2 3 4]))))
              st/emit! (mock/stub (fn [& evts] (swap! emitted into evts) nil))}
             (await (async/observe (df/process-upload [blob] team-id)
                                   :on-next #(reset! result %)))))
    (t/is (= {} @result))
    (t/is (= 1 (count @emitted)))
    (t/is (= :app.main.data.notifications/show (ptk/type (first @emitted))))))

(t/deftest ^:async process-upload-woff2-succeeds-from-filename
  (let [team-id (uuid/next)
        blob    #js {:name "Satoshi-Bold.woff2"}
        emitted (atom [])
        result  (atom nil)]
    (await (mock/with-mocks*
             {wa/read-file-as-array-buffer (mock/stub (fn [_] (rx/of (bytes->buffer [119 79 70 50 0 0 0 0]))))
              st/emit! (mock/stub (fn [& evts] (swap! emitted into evts) nil))}
             (await (async/observe (df/process-upload [blob] team-id)
                                   :on-next #(reset! result %)))
             (await (async/settle))))
    (t/is (= 1 (count (vals @result))))
    (let [entry (first (vals @result))]
      (t/is (= "Satoshi" (:font-family entry)))
      (t/is (= 700 (:font-weight entry)))
      (t/is (= "normal" (:font-style entry))))
    (t/is (empty? @emitted))))
