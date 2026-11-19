;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.imports-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.imports :as imports]
   [app.main.repo :as rp]
   [app.util.sse :as sse]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]))

(defn- make-blob
  [size]
  (js/Blob. #js [(js/Uint8Array. size)] #js {:type "application/octet-stream"}))

(t/deftest ^:async import-files-uses-kept-blob
  (let [session-id (uuid/next)
        file-id    (uuid/next)
        project-id (uuid/next)
        blob       (make-blob 30)
        uri        "blob:https://localhost/test-import"
        seen-cmds  (atom [])
        fetch-uris (atom [])
        seen       (atom [])
        orig-fetch (.-fetch js/globalThis)]

    (set! (.-fetch js/globalThis)
          (fn [url _opts]
            (swap! fetch-uris conj (str url))
            (js/Promise.reject (js/Error. "fetch must not run for import"))))

    (await
     (mock/with-mocks*
       {rp/cmd!
        (mock/stub
         (fn [cmd params]
           (swap! seen-cmds conj [cmd (dissoc params :content)])
           (cond
             (= cmd :create-upload-session)
             (rx/of {:session-id session-id})

             (= cmd :upload-chunk)
             (rx/of {:session-id session-id :index (:index params)})

             (= cmd ::sse/import-binfile)
             (rx/of #js {:type "end" :data {:resolution {}}})

             :else
             (rx/throw (ex-info "unexpected cmd" {:cmd cmd})))))}

       (await
        (hva/observe
         (imports/import-files
          {:project-id project-id
           :files [{:type :binfile-v3
                    :uri uri
                    :blob blob
                    :name "Package"
                    :file-id file-id}]})
         {:on-next #(swap! seen conj %)}))))

    (set! (.-fetch js/globalThis) orig-fetch)

    (t/is (= [:create-upload-session :upload-chunk ::sse/import-binfile]
             (mapv first @seen-cmds)))
    (t/is (= session-id (-> @seen-cmds (nth 2) second :upload-id)))
    (t/is (= [{:status :finish :file-id file-id}
              {:libraries-resolution {}}]
             @seen))
    (t/is (empty? @fetch-uris))))
