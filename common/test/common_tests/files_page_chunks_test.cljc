;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files-page-chunks-test
  (:require
   [app.common.files.page-chunks :as chunks]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest chunk-path-derives-from-the-page-and-the-index
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    (t/is (= (str "files/" file-id "/pages/" page-id "/chunks/0.json")
             (chunks/chunk-path file-id page-id 0)))
    (t/is (= (chunks/chunk-path file-id page-id 7)
             (chunks/chunk-path (str file-id) (str page-id) "7"))
          "a uuid and its string build the same path")))

(t/deftest default-chunk-size-stays-a-quarter-of-the-import-cap
  ;; The cap (`default-max-text-entry-size`, 20 MiB) lives in the backend,
  ;; and the backend test asserts the relation between the two so they
  ;; cannot drift apart.
  (t/is (= (* 5 1024 1024) chunks/default-chunk-size)))

(defn- serialize-size
  "A serializer that answers `n` x's for a value `n`, so the line weighs `n`
  plus one for the newline. It lets a test reason with plain numbers."
  [_id n]
  (apply str (repeat n "x")))

(t/deftest utf8-byte-size-counts-utf8-bytes
  (t/is (= 0 (chunks/utf8-byte-size "")))
  (t/is (= 3 (chunks/utf8-byte-size "abc"))
        "ascii weighs its length")
  (t/is (= 2 (chunks/utf8-byte-size "\u00e9")))
  (t/is (= 3 (chunks/utf8-byte-size "\u20ac")))
  (t/is (= 4 (chunks/utf8-byte-size "\uD83D\uDE00"))
        "a surrogate pair weighs four bytes")
  (t/is (= 7 (chunks/utf8-byte-size "a\u00e9\uD83D\uDE00"))))

(t/deftest chunk-full?-counts-the-line-and-the-budget
  (t/testing "the line bytes count towards the budget"
    (t/is (false? (chunks/chunk-full? 10 10 20))
          "10 + 10 is exactly the budget")
    (t/is (true? (chunks/chunk-full? 10 10 19))))

  (t/testing "an entry bigger than the budget only fits in a chunk of its own"
    (t/is (true? (chunks/chunk-full? 0 30 25)))))

(t/deftest chunk-lines-keeps-the-order-and-the-budget
  (t/testing "an empty page has no chunks"
    (t/is (= [] (vec (chunks/chunk-lines (array-map) 10 serialize-size)))))

  (t/testing "entries that fit stay in one chunk"
    (t/is (= [["xxx" "xxxx"]]
             (vec (chunks/chunk-lines (array-map :a 3 :b 4) 9 serialize-size))))
    (t/is (= [["xxx"] ["xxxx"]]
             (vec (chunks/chunk-lines (array-map :a 3 :b 4) 8 serialize-size)))
          "the lines count against the budget"))

  (t/testing "a chunk is started when the next entry would pass it"
    (t/is (= [["xxxxxxxxxx" "xxxxxxxxxx"] ["xxxxxxxxxx"]]
             (vec (chunks/chunk-lines (array-map :a 10 :b 10 :c 10)
                                      22 serialize-size)))))

  (t/testing "an entry bigger than the budget forms a chunk of its own"
    (t/is (= [["xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"] ["xxxxxxxxx"]]
             (vec (chunks/chunk-lines (array-map :a 30 :b 9) 40 serialize-size))))
    (t/is (= [["xxxxxxxxxx"] ["xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"] ["xxxxxxxxxx"]]
             (vec (chunks/chunk-lines (array-map :a 10 :b 30 :c 10)
                                      35 serialize-size)))
          "and the entries around it are not dragged into that chunk"))

  (t/testing "the size counts UTF-8 bytes and not characters"
    (let [ser (fn [_ _] "\u00e9")]        ; two bytes plus the newline
      (t/is (= [["\u00e9"] ["\u00e9"]]
               (vec (chunks/chunk-lines (array-map :a 1 :b 1) 5 ser))))
      (t/is (= [["\u00e9" "\u00e9"]]
               (vec (chunks/chunk-lines (array-map :a 1 :b 1) 6 ser)))))))

(t/deftest chunk-lines-is-lazy-and-serializes-each-line-once
  (let [calls   (atom 0)
        ser     (fn [_ n] (swap! calls inc) (apply str (repeat n "x")))
        objects (into (array-map)
                      (map (fn [i] [(keyword (str "k" i)) 3]))
                      (range 6))]

    ;; Six lines of four bytes; a budget of eight fits two per chunk.
    (t/is (= ["xxx" "xxx"] (first (chunks/chunk-lines objects 8 ser))))
    (t/is (= 3 @calls)
          "the first chunk plus the line that starts the next one, and no more")

    (reset! calls 0)
    (t/is (= 3 (count (vec (chunks/chunk-lines objects 8 ser)))))
    (t/is (= 6 @calls) "every line is serialized once when all chunks are read")))

(t/deftest codec-chunk-path-names-the-codec
  (let [file-id (uuid/next)
        page-id (uuid/next)]
    (t/is (= (chunks/chunk-path file-id page-id 0)
             (chunks/codec-chunk-path file-id page-id 0 nil)))
    (t/is (= (chunks/compressed-chunk-path file-id page-id 0)
             (chunks/codec-chunk-path file-id page-id 0 :zstd)))
    (t/is (= (str "files/" file-id "/pages/" page-id "/chunks/3.json.lz4")
             (chunks/codec-chunk-path file-id page-id 3 :lz4))
          "any codec names its suffix from the keyword")))
