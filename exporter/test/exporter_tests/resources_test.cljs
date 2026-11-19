;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.resources-test
  "Zip resource management tests."
  (:require
   ["node:fs" :as fs]
   ["node:path" :as path]
   [app.common.uuid :as uuid]
   [app.handlers.resources :as rsc]
   [app.util.shell :as sh]
   [cljs.test :as t :include-macros true]
   [promesa.core :as p]))

(def ^:private eocd-sig
  (js/Buffer.from #js [0x50 0x4b 0x05 0x06]))

(defn- once
  [f]
  (let [called (volatile! false)]
    (fn [& args]
      (when-not @called
        (vreset! called true)
        (apply f args)))))

(defn- source-file
  []
  (let [path (-> (path/join sh/tmpdir (str "penpot.test.entry." (uuid/next) ".bin"))
                 (sh/schedule-deletion))]
    (fs/writeFileSync path (js/Buffer.alloc 65536 1))
    path))

(t/deftest close-zip-resolves-with-a-complete-file
  "After `close-zip` resolves, the destination file must contain the
   whole archive, including the end-of-central-directory record. The
   fixture is sized to make it likely that data is still buffered when
   the archiver completes, so resolution must wait for the destination
   stream to finish/close (not only for archiver events)."
  (t/async done
    (let [done  (once done)
          timer (js/setTimeout #(do (t/is false "test timeout") (done)) 15000)]
      (-> (p/let [resource  (rsc/create :zip "resources-test")
                  [zip out] (rsc/create-zip :resource resource
                                            :on-error (fn [_] nil)
                                            :on-progress (fn [_] nil))
                  _         (doseq [i (range 20)]
                              (rsc/add-to-zip zip (source-file) (str "entry-" i ".bin")))
                  _         (rsc/close-zip zip out)]
            (let [^js buf (fs/readFileSync (:path resource))
                  eocd    (.lastIndexOf buf eocd-sig)]
              (t/is (not= -1 eocd)
                    "end-of-central-directory record present")
              (when (not= -1 eocd)
                ;; EOCD is 22 bytes + comment length (at offset 20, little-endian).
                (let [comment-len (.readUInt16LE buf (+ eocd 20))]
                  (t/is (= (.-length buf) (+ eocd 22 comment-len))
                        "archive has no missing tail")))))
          (p/catch (fn [err]
                     (t/is (nil? err) (str "unexpected error: " err))))
          (p/finally (fn [& _]
                       (js/clearTimeout timer)
                       (done)))))))

(t/deftest close-zip-rejects-on-destination-error
  "A failure on the destination stream must surface as a rejection
   instead of silently resolving with a truncated file."
  (t/async done
    (let [done     (once done)
          timer    (js/setTimeout #(do (t/is false "test timeout") (done)) 15000)
          bad-path (path/join sh/tmpdir (str (uuid/next)) (str (uuid/next) ".zip"))
          resource {:path bad-path}
          [zip out] (rsc/create-zip :resource resource
                                    :on-error (fn [_] nil)
                                    :on-progress (fn [_] nil))]
      (-> (p/let [_ (rsc/close-zip zip out)]
            (t/is false "close-zip should reject when the destination stream errors"))
          (p/catch (fn [_cause]
                     (t/is true "rejected on destination stream error")))
          (p/finally (fn [& _]
                       (js/clearTimeout timer)
                       (done)))))))
