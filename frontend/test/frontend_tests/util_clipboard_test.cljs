;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.util-clipboard-test
  "Regression tests for `to-clipboard-multi` (issue #10596).

  Browsers keep an allowlist of MIME types accepted by the async
  Clipboard API. Firefox only accepts text/plain, text/html and
  image/png, so writing a ClipboardItem that carries image/svg+xml
  rejects with `DOMException: Type 'image/svg+xml' not supported for
  write`. `clipboard.write` is all-or-nothing, so the text/plain
  representation is lost too and the rejection escapes unhandled."
  (:require
   [app.util.clipboard :as clipboard]
   [cljs.test :as t :include-macros true]))

(def ^:private svg-markup "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")

(defonce ^:private original-clipboard-item
  (unchecked-get js/globalThis "ClipboardItem"))

(defonce ^:private original-clipboard
  (unchecked-get js/navigator "clipboard"))

(defn- restore-globals!
  []
  (unchecked-set js/globalThis "ClipboardItem" original-clipboard-item)
  (unchecked-set js/navigator "clipboard" original-clipboard))

(t/use-fixtures :each {:after restore-globals!})

(defn- install-clipboard-item!
  "Install a `ClipboardItem` stub that records the MIME keys it was
  built with. When `supported` is provided it is exposed as the
  `ClipboardItem.supports` static, mirroring the browsers that ship it."
  [supported]
  (let [ctor (fn [obj]
               ;; a constructor returning an object hands that object back to `new`
               #js {:types (js/Object.keys obj)})]
    (when (some? supported)
      (unchecked-set ctor "supports" (fn [mime] (contains? supported mime))))
    (unchecked-set js/globalThis "ClipboardItem" ctor)))

(defn- install-navigator-clipboard!
  "Install a `navigator.clipboard` stub. `writable` is the set of MIME
  types the fake browser accepts on `write`; any item carrying a MIME
  outside of it rejects the whole write, as real browsers do."
  [state writable]
  (unchecked-set
   js/navigator "clipboard"
   #js {:write
        (fn [items]
          (let [types (vec (unchecked-get (aget items 0) "types"))]
            (swap! state update :written conj types)
            (if (every? writable types)
              (js/Promise.resolve)
              (js/Promise.reject
               (js/Error. (str "Type '" (first (remove writable types))
                               "' not supported for write"))))))
        :writeText
        (fn [text]
          (swap! state assoc :text text)
          (js/Promise.resolve))}))

(defn- copy-svg
  []
  (clipboard/to-clipboard-multi
   {"image/svg+xml" svg-markup
    "text/plain"    svg-markup}))

(t/deftest firefox-without-supports-falls-back-to-text
  (t/testing "#10596: a browser that rejects image/svg+xml must not leak a rejection"
    (t/async done
      (let [state (atom {:written []})]
        (install-clipboard-item! nil)
        (install-navigator-clipboard! state #{"text/plain" "text/html" "image/png"})
        (-> (copy-svg)
            (.then (fn [_]
                     (t/is (= svg-markup (:text @state))
                           "the SVG markup must still reach the clipboard as text")
                     (done)))
            (.catch (fn [err]
                      (t/is false (str "to-clipboard-multi must not reject: " err))
                      (done))))))))

(t/deftest firefox-with-supports-writes-only-the-supported-mimes
  (t/testing "#10596: ClipboardItem.supports filters the unsupported MIME out"
    (t/async done
      (let [state (atom {:written []})]
        (install-clipboard-item! #{"text/plain" "text/html" "image/png"})
        (install-navigator-clipboard! state #{"text/plain" "text/html" "image/png"})
        (-> (copy-svg)
            (.then (fn [_]
                     (t/is (= [["text/plain"]] (:written @state))
                           "the unsupported MIME must be filtered before writing")
                     (done)))
            (.catch (fn [err]
                      (t/is false (str "to-clipboard-multi must not reject: " err))
                      (done))))))))

(t/deftest chrome-keeps-the-svg-representation
  (t/testing "#10596: browsers that accept image/svg+xml must still get it"
    (t/async done
      (let [state (atom {:written []})
            mimes #{"image/svg+xml" "text/plain"}]
        (install-clipboard-item! mimes)
        (install-navigator-clipboard! state mimes)
        (-> (copy-svg)
            (.then (fn [_]
                     (t/is (= 1 (count (:written @state))))
                     (t/is (= mimes (set (first (:written @state))))
                           "dropping image/svg+xml would regress copy into Illustrator/Inkscape")
                     (t/is (nil? (:text @state))
                           "the writeText fallback must not be used when write succeeds")
                     (done)))
            (.catch (fn [err]
                      (t/is false (str "to-clipboard-multi must not reject: " err))
                      (done))))))))

(t/deftest unrelated-write-errors-are-not-swallowed
  (t/testing "a rejection unrelated to the MIME allowlist must not degrade to text"
    (t/async done
      (let [state (atom {:written []})]
        (install-clipboard-item! nil)
        (unchecked-set
         js/navigator "clipboard"
         #js {:write
              (fn [_]
                (js/Promise.reject (js/Error. "Document is not focused.")))
              :writeText
              (fn [text]
                (swap! state assoc :text text)
                (js/Promise.resolve))})
        (-> (copy-svg)
            (.then (fn [_]
                     (t/is false "an unrelated failure must reject, not silently write text")
                     (done)))
            (.catch (fn [err]
                      (t/is (= "Document is not focused." (ex-message err))
                            "the original cause must reach the caller")
                      (t/is (nil? (:text @state))
                            "no text must be written on an unrelated failure")
                      (done))))))))

(t/deftest missing-clipboard-item-ctor-falls-back-to-text
  (t/testing "`clipboard.write` without a `ClipboardItem` constructor must not throw"
    (t/async done
      (let [state (atom {:written []})]
        (unchecked-set js/globalThis "ClipboardItem" nil)
        (install-navigator-clipboard! state #{"text/plain"})
        (-> (copy-svg)
            (.then (fn [_]
                     (t/is (= [] (:written @state)))
                     (t/is (= svg-markup (:text @state))
                           "the payload must still reach the clipboard as text")
                     (done)))
            (.catch (fn [err]
                      (t/is false (str "to-clipboard-multi must not reject: " err))
                      (done))))))))

(t/deftest write-unavailable-uses-write-text
  (t/async done
    (let [state (atom {:written []})]
      (install-clipboard-item! nil)
      (unchecked-set js/navigator "clipboard"
                     #js {:writeText (fn [text]
                                       (swap! state assoc :text text)
                                       (js/Promise.resolve))})
      (-> (clipboard/to-clipboard-multi {"image/svg+xml" svg-markup
                                         "text/plain"    svg-markup})
          (.then (fn [_]
                   (t/is (= svg-markup (:text @state)))
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (done)))))))

(t/deftest empty-items-resolve-without-touching-the-clipboard
  (t/async done
    (let [state (atom {:written []})]
      (install-clipboard-item! nil)
      (install-navigator-clipboard! state #{"text/plain"})
      (-> (clipboard/to-clipboard-multi {})
          (.then (fn [_]
                   (t/is (= [] (:written @state)))
                   (t/is (nil? (:text @state)))
                   (done)))
          (.catch (fn [err]
                    (t/is false (str "unexpected rejection: " err))
                    (done)))))))

(t/deftest clipboard-unavailable-rejects
  (t/async done
    (install-clipboard-item! nil)
    (unchecked-set js/navigator "clipboard" nil)
    (-> (clipboard/to-clipboard-multi {"text/plain" svg-markup})
        (.then (fn [_]
                 (t/is false "must reject when the Clipboard API is unavailable")
                 (done)))
        (.catch (fn [err]
                  (t/is (instance? js/Error err))
                  (done))))))
