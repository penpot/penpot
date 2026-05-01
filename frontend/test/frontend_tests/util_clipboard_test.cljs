;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-clipboard-test
  "Unit tests for the navigator.clipboard availability predicates introduced
  in the secure-context fallback for [Github #8496]."
  (:require
   [app.util.clipboard :as clipboard]
   [cljs.test :as t :include-macros true]))

(t/deftest clipboard-write-text-supported?-test
  (t/testing "Returns false when navigator.clipboard is undefined"
    (t/is (false? (clipboard/clipboard-write-text-supported? nil)))
    (t/is (false? (clipboard/clipboard-write-text-supported? js/undefined))))

  (t/testing "Returns false when clipboard exists but lacks writeText"
    ;; Browsers gate even the bare `navigator.clipboard` object behind a
    ;; secure context in some environments; an empty object should not be
    ;; treated as supporting the async API.
    (t/is (false? (clipboard/clipboard-write-text-supported? (js-obj))))
    (t/is (false? (clipboard/clipboard-write-text-supported?
                   (js-obj "read" (fn [] nil))))))

  (t/testing "Returns true when clipboard exposes writeText"
    (t/is (true? (clipboard/clipboard-write-text-supported?
                  (js-obj "writeText" (fn [_] (js/Promise.resolve)))))))

  (t/testing "Returns true even when writeText is co-located with other APIs"
    (t/is (true? (clipboard/clipboard-write-text-supported?
                  (js-obj "writeText" (fn [_] (js/Promise.resolve))
                          "write"     (fn [_] (js/Promise.resolve))
                          "read"      (fn []  (js/Promise.resolve []))))))))

(t/deftest clipboard-write-supported?-test
  (t/testing "Returns false when navigator.clipboard is undefined"
    (t/is (false? (clipboard/clipboard-write-supported? nil)))
    (t/is (false? (clipboard/clipboard-write-supported? js/undefined))))

  (t/testing "Returns false when clipboard exists but lacks write"
    ;; A clipboard object that only supports the older writeText API does
    ;; not support rich (ClipboardItem) writes; callers must reject rather
    ;; than fall through to a non-existent .write call.
    (t/is (false? (clipboard/clipboard-write-supported? (js-obj))))
    (t/is (false? (clipboard/clipboard-write-supported?
                   (js-obj "writeText" (fn [_] (js/Promise.resolve)))))))

  (t/testing "Returns true when clipboard exposes write"
    (t/is (true? (clipboard/clipboard-write-supported?
                  (js-obj "write" (fn [_] (js/Promise.resolve)))))))

  (t/testing "Returns true even when write is co-located with other APIs"
    (t/is (true? (clipboard/clipboard-write-supported?
                  (js-obj "writeText" (fn [_] (js/Promise.resolve))
                          "write"     (fn [_] (js/Promise.resolve))))))))

(t/deftest predicates-are-independent
  (t/testing "writeText support does not imply rich write support"
    (let [text-only (js-obj "writeText" (fn [_] (js/Promise.resolve)))]
      (t/is (true?  (clipboard/clipboard-write-text-supported? text-only)))
      (t/is (false? (clipboard/clipboard-write-supported?      text-only)))))

  (t/testing "Rich write support does not imply writeText support"
    ;; Defensive: spec-wise both ship together, but predicates should treat
    ;; the surface independently so a partial polyfill does not crash.
    (let [write-only (js-obj "write" (fn [_] (js/Promise.resolve)))]
      (t/is (false? (clipboard/clipboard-write-text-supported? write-only)))
      (t/is (true?  (clipboard/clipboard-write-supported?      write-only))))))
