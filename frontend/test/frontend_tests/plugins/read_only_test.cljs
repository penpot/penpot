;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.read-only-test
  "Plugin writes in a read-only workspace."
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.media :as dwm]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.register :as r]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

;; Its validation flag is off, so only errors that always throw reach it
(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(defn- set-can-edit
  [store can-edit?]
  (swap! store assoc-in [:permissions :can-edit] can-edit?))

(defn- shape-count
  [store]
  (count (dsh/lookup-page-objects @store)))

(defn- with-context
  "Calls `f` with a store holding a sample file and a plugin context on it."
  [f]
  (thw/with-wasm-mocks*
    (fn []
      (let [store (ths/setup-store (cthf/sample-file :file1 :page-label :page1))]
        (set! st/state store)
        (f store (api/create-context plugin-id))))))

(t/deftest writes-throw-in-a-read-only-workspace
  (with-context
    (fn [store ^js context]
      (let [^js board (.createBoard context)]
        (set-can-edit store false)

        (t/testing "a shape setter"
          (t/is (thrown-with-msg? js/Error #"read-only" (set! (.-name board) "Renamed")))
          (t/is (not= "Renamed" (.-name board))))

        (t/testing "a shape method"
          (t/is (thrown-with-msg? js/Error #"read-only" (.bringToFront board))))

        (t/testing "a context method without a permission check"
          (let [before (shape-count store)]
            (t/is (thrown-with-msg? js/Error #"read-only" (.createRectangle context)))
            (t/is (= before (shape-count store)))))

        (t/testing "a context method with a permission check"
          (t/is (thrown-with-msg? js/Error #"read-only" (.createPage (.-currentFile context)))))))))

(t/deftest writes-work-again-once-the-workspace-is-editable
  (with-context
    (fn [store ^js context]
      (let [^js board (.createBoard context)]
        (set-can-edit store false)
        (set-can-edit store true)
        (set! (.-name board) "Renamed")
        (t/is (= "Renamed" (.-name board)))))))

(t/deftest the-inspect-tab-does-not-block-writes
  (with-context
    (fn [store ^js context]
      (let [^js board (.createBoard context)]
        (swap! store update :workspace-global assoc
               :read-only? true
               :options-mode :inspect)
        (set! (.-name board) "Renamed")
        (t/is (= "Renamed" (.-name board)))))))

(t/deftest each-read-only-source-says-why
  (doseq [[path value reason] [[[:permissions :can-edit] false #"cannot edit this file"]
                               [[:workspace-global :preview-id] (random-uuid) #"version is being previewed"]
                               [[:render-state :lost] true #"canvas stopped rendering"]]]
    (with-context
      (fn [store ^js context]
        (swap! store assoc-in path value)
        (t/testing (str path)
          (t/is (thrown-with-msg? js/Error reason (.createBoard context))))))))

(t/deftest a-save-failure-lock-says-why-the-workspace-is-read-only
  (with-context
    (fn [store ^js context]
      (swap! store update :workspace-global assoc
             :read-only? true
             :persistence-lock {:read-only? false})
      (t/is (thrown-with-msg? js/Error #"cannot be saved" (.createBoard context))))))

(t/deftest reads-and-undo-blocks-work-in-a-read-only-workspace
  (with-context
    (fn [store ^js context]
      (let [^js board (.createBoard context)]
        (set-can-edit store false)
        (t/is (string? (.-name board)))
        (let [block (.undoBlockBegin (.-history context))]
          (t/is (some? block))
          (t/is (nil? (.undoBlockFinish (.-history context) block))))))))

(t/deftest read-only-keeps-permission-checks-of-reads
  (with-context
    (fn [store _]
      (set-can-edit store false)
      (t/is (true? (r/check-permission plugin-id "content:read")))
      (t/is (true? (r/has-permission? plugin-id "content:write")))
      (t/is (thrown? js/Error (r/check-permission plugin-id "library:write"))))))

;; Writes that wait for the server before they change the file

(defn- settled
  "Resolves with `{:resolved value}` or `{:rejected cause}` once `p` settles,
  or with `{:timeout true}` when it stays pending for 2 seconds."
  [p]
  (js/Promise.race
   #js [(.then p (fn [v] {:resolved v}) (fn [e] {:rejected e}))
        (js/Promise. (fn [resolve] (js/setTimeout #(resolve {:timeout true}) 2000)))]))

(defn- lock-saves
  "Locks `store` as a failing save does."
  [store]
  (swap! store update :workspace-global assoc
         :read-only? true
         :persistence-lock {:read-only? false}))

(defn- held-rpc
  "An `rp/cmd!` that records each command in `calls` and answers with the
  next value pushed to `answer`."
  [calls answer]
  (mock/stub (fn [cmd _]
               (swap! calls conj cmd)
               (->> answer (rx/take 1) (rx/observe-on :async)))))

(def ^:private media-object
  {:id (uuid/next) :name "image" :width 10 :height 10 :mtype "image/png"})

(def ^:private svg-with-image
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
       "<image href=\"data:image/png;base64,iVBORw0KGgo=\" width=\"10\" height=\"10\"/>"
       "</svg>"))

(defn- parsed-svg-with-image
  "What `dwm/svg->clj` gives for `svg-with-image`. The test build cannot
  load the XML parser, a CommonJS module."
  [[name _]]
  (rx/of {:tag :svg
          :name name
          :attrs {:xmlns "http://www.w3.org/2000/svg" :width "10" :height "10"}
          :content [{:tag :image
                     :attrs {:href "data:image/png;base64,iVBORw0KGgo=" :width "10" :height "10"}
                     :content []}]}))

(defn- read-only-error?
  [cause]
  (boolean (some->> cause ex-message (re-find #"cannot be saved"))))

;; Scenario: an SVG with an image is created; while its image uploads a
;; save fails and locks the workspace. Proves: the shape is not added and
;; the promise rejects with the read-only error.
(t/deftest ^:async an-svg-whose-upload-ends-after-a-save-failure-is-refused
  (thw/setup-wasm-mocks!)
  (try
    (let [store  (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
          calls  (atom [])
          answer (rx/subject)]
      (await
       (mock/with-mocks*
         {st/state                store
          rp/cmd!                 (held-rpc calls answer)
          dwm/valid-svg-string?   (constantly true)
          dwm/svg->clj            (mock/stub parsed-svg-with-image)}
         (let [^js context (api/create-context plugin-id)
               before      (shape-count store)
               result      (settled (.createShapeFromSvgWithImages context svg-with-image))]
           (await (async/wait-for #(= [:upload-file-media-object] @calls) "the image upload starts"))
           (lock-saves store)
           (rx/push! answer media-object)
           (let [{:keys [rejected]} (await result)]
             (t/is (read-only-error? rejected))
             (await (async/settle))
             (t/is (= before (shape-count store)) "no shape is added"))))))
    (finally
      (thw/teardown-wasm-mocks!))))

;; Uploads `upload` with the context; while the request for `cmd` runs, a
;; save fails and locks the workspace.
(defn- ^:async upload-during-a-save-failure
  [upload cmd]
  (let [store  (ths/setup-store (cthf/sample-file :file1 :page-label :page1))
        calls  (atom [])
        answer (rx/subject)]
    (await
     (mock/with-mocks*
       {st/state store
        rp/cmd!  (held-rpc calls answer)}
       ;; Bound one by one: a nested call with a local fn would compile to
       ;; an awaited IIFE, which waits for the upload itself
       (let [context (api/create-context plugin-id)
             result  (settled (upload context))]
         (await (async/wait-for #(= [cmd] @calls) "the upload starts"))
         (lock-saves store)
         (rx/push! answer media-object)
         (let [{:keys [rejected]} (await result)]
           (t/is (read-only-error? rejected) (str cmd))))))))

;; Scenario: an image is uploaded from data and from a URL; while each
;; upload runs a save fails and locks the workspace. Proves: the promise
;; rejects with the read-only error. The media object stays on the server.
(t/deftest ^:async media-uploaded-after-a-save-failure-is-refused
  (thw/setup-wasm-mocks!)
  (try
    (await (upload-during-a-save-failure
            #(.uploadMediaData ^js % "image" (js/Uint8Array. #js [1 2 3]) "image/png")
            :upload-file-media-object))
    (await (upload-during-a-save-failure
            #(.uploadMediaUrl ^js % "image" "https://example.com/image.png")
            :create-file-media-object-from-url))
    (finally
      (thw/teardown-wasm-mocks!))))
