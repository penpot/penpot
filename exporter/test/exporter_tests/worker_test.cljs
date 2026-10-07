;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.worker-test
  "The queue loop of the worker: the payload it reads, the claim it
  makes, and one settled export end to end, over a fake redis queue and
  a fake management http. The e2e against the real backend is Task 8's
  business in devenv; this harness pins the shapes the worker owns."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.consumer.api :as api]
   [app.consumer.exports :as exports]
   [app.consumer.worker :as worker]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [promesa.core :as p]))

;; ---- THE MANAGEMENT FAKE

(def ^:private original-fetch api/fetch)

(def ^:private calls
  "One entry per management call: [method, body]."
  (atom []))

(defn- method-of
  "The management method the client asked for."
  [uri]
  (last (str/split (str uri) "/")))

(defn- steps-of
  "The sequence of management methods the run went by."
  []
  (mapv first @calls))

(defn- call-of
  "The body of the first call of one method."
  [method]
  (some (fn [[m body]]
          (when (= m method) body))
        @calls))

(defn- fake-fetch!
  "Installs a management http fake: `respond` answers with the `:action`
  envelope per [method, body] (the multipart arrives as a FormData
  instance). Every call lands in `calls` before the answer. Returns the
  restore thunk."
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
              (p/resolved #js {:status 200
                               :text   (constantly
                                        (p/resolved (transit/encode-str
                                                     (respond method body))))}))))
    (fn [] (set! api/fetch original))))

;; ---- THE RD FAKE

(def ^:private original-render rd/render)

(defn- fake-render!
  "Installs a fake renderer that writes one file of `content` per
  object and calls back with it. Returns the restore thunk."
  [content]
  (let [original rd/render]
    (set! rd/render
          (fn [_params on-object]
            (p/let [rendered-path (sh/tempfile :prefix "penpot.render."
                                               :suffix ".png")
                    _             (fsp/writeFile rendered-path content)]
              (on-object {:path     rendered-path
                          :filename "rendered.png"})
              (p/resolved nil))))
    (fn [] (set! rd/render original))))

(defn- fake-render-fail!
  "A renderer that always fails: the failure path of the run."
  []
  (let [original rd/render]
    (set! rd/render (fn [_params _on-object]
                      (p/rejected (ex-info "render boom" {}))))
    (fn [] (set! rd/render original))))

;; ---- THE QUEUE FAKE

(defn- fake-conn!
  "A fake ioredis connection: `blpop` pops one payload from the queue
  the atom holds, or nil."
  [queue]
  #js {:blpop (fn [_key _timeout-s]
                (if-let [payload (first @queue)]
                  (do
                    (swap! queue rest)
                    (p/resolved #js ["the.queue.key" payload]))
                  (p/resolved nil)))})

;; ---- THE FIXTURE

(defn- export-params
  "Params of a one-shape png job, in the job-def shape, the way the
  claim carries them: the type as text."
  []
  {:exports [{:file-id    (str (uuid/next))
              :page-id    (str (uuid/next))
              :object-id  (str (uuid/next))
              :type       "png"
              :name       "test shape"
              :suffix     ""
              :scale      1}]
   :name    "the export"})

(defn- encoded-claim
  "The JSON payload the dispatcher pushes."
  [job-id scheduled-at]
  (js/JSON.stringify #js [(str job-id) scheduled-at]))

(defn- answer-with
  "The management answers of a run: the claim gives the run away to
  this poller with the params of the job, the session mints a real
  one, every report and settle answers run. The `stages` atom records
  every report's stage (they travel wrapped in `:progress`)."
  [params sid stages]
  (fn [method body]
    (when (= method "report-job-progress")
      (vswap! stages conj (get-in body [:progress :stage])))
    (case method
      "claim-job"          {:action :run :name "export-assets" :params params}
      "create-job-session" {:session-id sid :session-token "session-token"}
      {:action :run})))

(defn- run-claim
  "The claim payload of one call of `process!`."
  [job-id]
  {:job-id      job-id
   :scheduled-at "2026-10-07T09:00:00Z"})

(t/deftest read-decodes-the-dispatcher-payload
  (t/testing "a payload in shape names the job and when it was pushed"
    (let [job-id (uuid/next)]
      (t/is (= {:job-id       (str job-id)
                :scheduled-at "2026-10-07T09:00:00Z"}
               (worker/read-payload! (encoded-claim job-id
                                                    "2026-10-07T09:00:00Z"))))))


  (t/testing "a corrupt payload is dropped, not thrown at the loop"
    (t/is (nil? (worker/read-payload! "{not a payload")))))

(t/deftest poll-once-awaits-what-it-claims
  (t/async done
    (let [queue   (atom [(encoded-claim (uuid/next) "2026-10-07T09:00:00Z")])
          conn    (fake-conn! queue)
          handled (atom [])]
      (p/let [_ (worker/poll-once! conn
                                   "the.queue.key"
                                   (fn [claim] (swap! handled conj claim)))]
        (t/is (= 1 (count @handled)))
        (done)))))

(t/deftest poll-once-drops-empty-pops-and-corrupt-payloads
  (t/async done
    (let [queue (atom ["{corrupt"])
          conn  (fake-conn! queue)]
      (p/let [_ (worker/poll-once! conn
                                   "the.queue.key"
                                   (fn [_claim]
                                     (t/is false "corrupt must not be handled")))
              _ (worker/poll-once! conn
                                   "the.queue.key"
                                   (fn [_claim]
                                     (t/is false "a corrupt pop has nothing to handle")))]
        (t/is (empty? @queue))
        (done)))))

(t/deftest process-drops-a-claim-the-worker-never-won
  (t/async done
    (let [respond!    (fake-fetch!
                       (fn [method _body]
                         (case method
                           "claim-job"  {:action :skip :status "cancelled"}
                           {:action :run})))
          render!     (fake-render! "should never render")]
      (p/let [settled (worker/process!
                       {:job-id      (uuid/next)
                        :scheduled-at "2026-10-07T09:00:00Z"})]
        (t/testing "only the claim traveled: no session, no report, no render"
          (t/is (= ["claim-job"] (steps-of)))
          (t/is (nil? settled)))
        (respond!)
        (render!)
        (done)))))

(t/deftest process-runs-the-claimed-export-to-a-multipart-settle
  (t/async done
    (let [job-id    (uuid/next)
          sid       (uuid/next)
          params    (export-params)
          stages    (volatile! [])
          respond!  (fake-fetch! (answer-with params sid stages))
          restore!  (fake-render! "rendered!")]
      (p/let [_ (worker/process! (run-claim job-id))]
        (t/testing "the run went by claim, session, beats, settle"
          (t/is (= ["claim-job" "create-job-session"
                    "report-job-progress" "report-job-progress" "report-job-progress"
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
        (respond!)
        (restore!)
        (done)))))

(t/deftest process-settles-a-failed-export-with-fail-job
  (t/async done
    (let [job-id    (uuid/next)
          sid       (uuid/next)
          params    (export-params)
          stages    (volatile! [])
          respond!  (fake-fetch! (answer-with params sid stages))
          restore!  (fake-render-fail!)]
      (p/let [_ (worker/process! (run-claim job-id))]
        (t/testing "the settle was a fail, not a complete"
          (t/is (= "fail-job" (last (steps-of)))))
        (t/testing "the failure is the shape the backend stores"
          (let [fail (call-of "fail-job")]
            (t/is (= :internal (get-in fail [:error :type])))
            (t/is (= :export-failed (get-in fail [:error :code])))
            (t/is (= "render boom" (get-in fail [:error :hint])))
            (t/is (= sid (:session-id fail)))))
        (respond!)
        (restore!)
        (done)))))

;; ---- THE CANCEL

(t/deftest cancel-runs-the-cancel-port
  ;; the beat the backend refuses (a skip) is the cancel arriving: the
  ;; port runs exactly once, the local flag says cancelled and the
  ;; runner unwinds; the settle below is the backend's own row, not a
  ;; new answer of this worker
  (t/async done
    (let [job-id   (uuid/next)
          sid      (uuid/next)
          params   (export-params)
          stages   (volatile! [])
          port     (atom 0)
          respond! (fake-fetch!
                    (fn [method body]
                      (when (= "report-job-progress" method)
                        (vswap! stages conj (get-in body [:progress :stage])))
                      (case method
                        "claim-job"          {:action :run :name "export-assets" :params params}
                        "create-job-session" {:session-id sid :session-token "session-token"}
                        "report-job-progress" {:action :skip}
                        {:action :run})))]
      (p/let [_ (worker/process! (run-claim job-id))]
        (t/testing "the settle of a cancelled job was a fail the backend answers skip"
          (t/is (= "fail-job" (last (steps-of))))
          (let [fail (call-of "fail-job")]
            (t/is (= :job-cancelled (get-in fail [:error :code])))))
        (respond!)
        (done)))))

(t/deftest cancel-kills-the-mid-skia-render
  ;; the watchdog is what makes the cancel seen while the thread renders;
  ;; the port it fires is the one renderer.wasm/with-scope registered,
  ;; which terminates the worker
  (t/async done
    (let [job-id        (uuid/next)
          sid           (uuid/next)
          params        (export-params)
          stages        (volatile! [])
          terminated    (atom 0)
          mark!         (atom nil)
          respond!      (fake-fetch!
                         (fn [method body]
                           (when (= "report-job-progress" method)
                             (vswap! stages conj (get-in body [:progress :stage])))
                           (case method
                             "claim-job"          {:action :run :name "export-assets" :params params}
                             "create-job-session" {:session-id sid :session-token "session-token"}
                             ;; the first beat runs, everything else skips:
                             ;; the cancel lands right after the claim
                             "report-job-progress" (if (empty? @stages)
                                                     {:action :run}
                                                     {:action :skip})
                             {:action :run})))
          render-restore (let [original rd/render]
                           (set! rd/render
                                 (fn [_params _on-object]
                                   ;; never resolves: the render only ends
                                   ;; when its thread is terminated, and the
                                   ;; registry entry the with-scope registered
                                   ;; does not exist in this fake
                                   (p/delay 30000)))
                           (fn [] (set! rd/render original)))]
      (p/let [_ (worker/process! (run-claim job-id))]
        (t/testing "the run ended in a fail job, not a complete"
          (t/is (= "fail-job" (last (steps-of)))))
        (render-restore)
        (respond!)
        (done)))))
