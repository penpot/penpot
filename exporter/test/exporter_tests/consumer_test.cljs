;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.consumer-test
  "The export runner of the new tree: plan → render batch → settle,
  over a fake management http and a stub renderer. The beats paint the
  stage vocabulary in order and the settle carries the artifact; a
  failure or a cancel settles as fail-job and never leaks the beat."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as cstr]
   [exporter.consumer :as consumer]
   [exporter.consumer.api :as api]
   [exporter.jobs :as jobs]
   [exporter.shell :as shell]))

;; ---- THE MANAGEMENT FAKE

(def ^:private calls
  "One entry per management call: [method, body]."
  (atom []))

(defn- method-of
  [uri]
  (last (cstr/split (str uri) "/")))

(defn- steps-of
  []
  (mapv first @calls))

(defn- call-of
  [method]
  (some (fn [[m body]]
          (when (= m method) body))
        @calls))

(defn- fake-fetch
  [respond]
  (reset! calls [])
  (let [original api/fetch]
    (set! api/fetch
          (fn [uri request]
            (let [method (method-of uri)
                  body   (if (instance? http/FormData (.-body request))
                           (.-body request)
                           (transit/decode-str (.-body request)))]
              (swap! calls conj [method body])
              (js/Promise.resolve #js {:status 200
                                       :text   (constantly
                                                (js/Promise.resolve
                                                 (transit/encode-str
                                                  (respond method body))))}))))
    (fn [] (set! api/fetch original))))

;; ---- THE RENDER STUB

(defn- stub-render
  "A render fn in place of the service: writes one file of `content`
  per export and calls back with it. When `captured` is an atom, it
  also keeps the last batch it received."
  ([content] (stub-render content nil))
  ([content captured]
   (fn [{:keys [exports on-object] :as task}]
     (when (some? captured)
       (reset! captured task))
     (js/Promise.
      (fn [resolve reject]
        (let [writes (mapv (fn [_]
                             (let [path (shell/tempfile (os/tmpdir) :prefix "penpot.render."
                                                        :suffix ".png")]
                               (.then (fsp/writeFile path content)
                                      (fn [_]
                                        (on-object {:path     path
                                                    :filename "rendered.png"})))))
                           exports)]
          (-> (js/Promise.all (clj->js writes))
              (.then (fn [_] (resolve nil)) reject))))))))

(defn- stub-render-fail
  []
  (fn [& _]
    (js/Promise.reject (ex-info "render boom" {}))))

;; ---- THE FIXTURE

(defn- export-params
  []
  {:exports [{:file-id   (str (uuid/next))
              :page-id   (str (uuid/next))
              :object-id (str (uuid/next))
              :type      "png"
              :name      "test shape"
              :suffix    ""
              :scale     1}]
   :name    "the export"})

(defn- answer-with
  [sid stages]
  (fn [method body]
    (when (= method "report-job-progress")
      (vswap! stages conj (get-in body [:progress :stage])))
    (case method
      "create-job-session" {:session-id sid :session-token "session-token"}
      {:action :run})))

(t/deftest ^:async run-exports-one-shape-to-a-multipart-settle
  (let [job-id         (uuid/next)
        sid            (uuid/next)
        stages         (volatile! [])
        restore-fetch  (fake-fetch
                        (fn [method body]
                          (when (= method "report-job-progress")
                            (vswap! stages conj (get-in body [:progress :stage])))
                          (case method
                            "create-job-session" {:session-id sid :session-token "session-token"}
                            {:action :run})))
        render         (stub-render "rendered!")]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (t/testing "the run went by first breath, session, beats, settle"
        (t/is (= ["report-job-progress" "create-job-session"
                  "report-job-progress" "report-job-progress"
                  "complete-job"]
                 (steps-of))))
      (t/testing "the settle carried the real artifact, named and typed"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= (str job-id) (.get fd "job-id")))
          (t/is (= (str sid) (.get fd "session-id")))
          (t/is (= "image/png" (.get fd "mtype")))))
      (t/testing "the beats painted the vocabulary in order"
        (t/is (= [:preparing :rendering :packaging] @stages)))
      (t/testing "the local registry entry is gone with the settle"
        (t/is (false? (jobs/cancelled? job-id))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-hands-the-renderer-uuids-not-claim-strings
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        captured       (atom nil)
        render         (stub-render "rendered!" captured)
        job-id         (uuid/next)
        share-id       (uuid/next)]
    (try
      (await (consumer/run-export
              render (os/tmpdir)
              {:job-id job-id}
              {:exports [{:file-id   (str (uuid/next))
                          :page-id   (str (uuid/next))
                          :object-id (str (uuid/next))
                          :share-id  (str share-id)
                          :type      "png"
                          :name      "test shape"
                          :suffix    ""
                          :scale     1}]
               :name    "the export"}))
      (let [export (-> @captured :exports first)]
        (t/testing "every id arrived as a uuid object"
          (t/is (uuid? (:file-id export)))
          (t/is (uuid? (:page-id export)))
          (t/is (uuid? (:share-id export)))
          (t/is (= share-id (:share-id export)))
          (t/is (uuid? (-> export :objects first :id))))
        (t/testing "the job-id traveled with the render"
          (t/is (= job-id (:job-id export))))
        (t/testing "the task carried both injections"
          (t/is (fn? (:on-object @captured)))
          (t/is (fn? (:check-cancelled @captured)))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-packs-two-shapes-as-zip
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render         (stub-render "rendered!")
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export
              render (os/tmpdir)
              {:job-id job-id}
              {:exports [{:file-id   (str (uuid/next))
                          :page-id   (str (uuid/next))
                          :object-id (str (uuid/next))
                          :type      "png"
                          :name      "one"
                          :suffix    ""
                          :scale     1}
                         {:file-id   (str (uuid/next))
                          :page-id   (str (uuid/next))
                          :object-id (str (uuid/next))
                          :type      "png"
                          :name      "two"
                          :suffix    ""
                          :scale     1}]
               :name    "the export"}))
      (t/testing "the artifact is the zip of both objects"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= "application/zip" (.get fd "mtype")))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async a-failed-run-settles-fail-job-and-stops-beating
  (let [restore-fetch  (fake-fetch (answer-with (uuid/next) (volatile! [])))
        render         (stub-render-fail)
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (await (js/Promise. (fn [resolve] (js/setTimeout resolve 1400))))
      (t/testing "the settle was a fail, not a complete"
        (t/is (= "fail-job" (last (steps-of)))))
      (t/testing "the failure is the shape the backend stores"
        (let [fail (call-of "fail-job")]
          (t/is (= :internal (get-in fail [:error :type])))
          (t/is (= :export-failed (get-in fail [:error :code])))
          (t/is (= "render boom" (get-in fail [:error :hint])))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async cancel-before-the-render-settles-cancelled
  (let [restore-fetch (fake-fetch
                       (fn [method _body]
                         (case method
                           "create-job-session" {:session-id (uuid/next)
                                                 :session-token "session-token"}
                           "report-job-progress" {:action :skip}
                           {:action :run})))
        render         (stub-render "should never render")
        job-id         (uuid/next)]
    (try
      (await (consumer/run-export render (os/tmpdir) {:job-id job-id} (export-params)))
      (t/testing "the settle of a cancelled job was a fail the backend answers skip"
        (t/is (= "fail-job" (last (steps-of))))
        (let [fail (call-of "fail-job")]
          (t/is (= :job-cancelled (get-in fail [:error :code])))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)))))

(t/deftest ^:async run-joins-frame-pages-as-pdf
  ;; the frames path end to end except pdfunite itself: the page
  ;; renders, the join runs in the injected temp area, the artifact
  ;; settles as pdf. Frames travel declared (`:kind`), not sniffed:
  ;; the items are fully typed pages, as the frozen job holds them.
  (let [tmpdir         (shell/ensure-dir (path/join (os/tmpdir) "penpot-consumer-frames-test"))
        restore-fetch  (fake-fetch
                        (fn [method _body]
                          (case method
                            "create-job-session" {:session-id   (uuid/next)
                                                  :session-token "session-token"}
                            {:action :run})))
        render         (stub-render "page!")
        joined         (atom nil)
        original-cmd   shell/run-cmd
        job-id         (uuid/next)]
    (set! shell/run-cmd
          (^:async fn [cmd & args]
            (let [dest (last (filter string? (flatten args)))]
              (reset! joined [cmd dest])
              (await (shell/write-file dest "joined!"))
              nil)))
    (try
      (await (consumer/run-export render tmpdir {:job-id job-id}
                                  {:exports [{:file-id   (str (uuid/next))
                                              :page-id   (str (uuid/next))
                                              :object-id (str (uuid/next))
                                              :type      "pdf"
                                              :name      "page"
                                              :suffix    ""
                                              :scale     1}]
                                   :kind    "frames"
                                   :name    "the export"}))
      (t/testing "pdfunite joined inside the injected temp area"
        (t/is (= "pdfunite" (first @joined)))
        (t/is (cstr/starts-with? (second @joined) tmpdir)))
      (t/testing "the artifact settled as pdf"
        (let [fd (call-of "complete-job")]
          (t/is (instance? http/FormData fd))
          (t/is (= "application/pdf" (.get fd "mtype")))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore-fetch)
        (set! shell/run-cmd original-cmd)))))
