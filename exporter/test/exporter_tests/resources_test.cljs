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

(def ^:private eocd-str
  (js/String.fromCharCode 0x50 0x4b 0x05 0x06))

(defn- source-file
  []
  (let [path (path/join (sh/tmpdir) (str (uuid/next) ".png"))]
    (fs/writeFileSync path (js/Buffer.alloc 4000))
    path))

(t/deftest close-zip-resolves-with-a-complete-file
  "After `close-zip` resolves, the destination file must contain the
   whole archive, including the end-of-central-directory record."
  (t/async done
    (let [resource (rsc/create :zip "resources-test")
          [zip out] (rsc/create-zip :resource resource
                                    :on-error (fn [_] nil)
                                    :on-progress (fn [_] nil))]
      (doseq [i (range 3)]
        (rsc/add-to-zip zip (source-file) (str "entry-" i ".png")))
      (-> (rsc/close-zip zip out)
          (p/fmap (fn [_]
                    (let [buf (fs/readFileSync (:path resource))
                          eocd (.lastIndexOf buf eocd-str)]
                      (t/is (not= -1 eocd) "end-of-central-directory record present")
                      (t/is (= (+ eocd 22) (.-length buf)) "archive has no missing tail")
                      (done nil))))
          (p/catch (fn [err]
                     (t/is nil (str "unexpected error: " err))
                     (done nil)))))))

(t/deftest close-zip-rejects-on-destination-error
  "A failure on the destination stream must surface as a rejection
   instead of silently resolving with a truncated file."
  (t/async done
    (let [bad-path (path/join (sh/tmpdir) (str (uuid/next)) (str (uuid/next) ".zip"))
          resource {:path bad-path}
          [zip out] (rsc/create-zip :resource resource
                                    :on-error (fn [_] nil)
                                    :on-progress (fn [_] nil))]
      (-> (rsc/close-zip zip out)
          (p/fmap (fn [_]
                    (t/is nil "close-zip should reject when the destination stream errors")
                    (done nil)))
          (p/catch (fn [_]
                     (t/is true)
                     (done nil)))))))
