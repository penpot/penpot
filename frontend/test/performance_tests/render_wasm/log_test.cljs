;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns performance-tests.render-wasm.log-test
  (:require
   ["fs" :as fs]
   ["node:stream" :as stream]
   ["node:timers/promises" :as timers]
   ["os" :as os]
   ["path" :as path]
   [benches.render-wasm.node.log :as log]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def temporary-root (atom nil))

(t/use-fixtures :each
  {:before #(reset! temporary-root (fs/mkdtempSync (path/join (os/tmpdir) "bench-log-")))
   :after (fn []
            (log/configure {})
            (fs/rmSync @temporary-root #js {:recursive true :force true}))})

(defn- capture
  []
  (let [text (atom "")
        out  (stream/Writable.
              #js {:write (fn [chunk _encoding done]
                            (swap! text str (.toString chunk))
                            (done))})]
    {:stream out :text text}))

(t/deftest levels-filter-console-and-retain-every-line-in-the-file
  (doseq [[level expected] [[:warn "! warning\n✖ failure\n"]
                            [:info "Phase\n  attempt 1/1 completed\n! warning\n✖ failure\n"]
                            [:debug "Phase\n  attempt 1/1 completed\n· raw child\n! warning\n✖ failure\n"]]]
    (let [stdout (capture)
          stderr (capture)
          file   (path/join @temporary-root (name level))]
      (log/configure {:level level :color :never
                      :stdout (:stream stdout) :stderr (:stream stderr)})
      (binding [log/*log-path* file]
        (log/info "Phase")
        (log/info "  attempt 1/1 completed")
        (log/debug "raw child")
        (log/warn "warning")
        (log/error {:message "failure" :tail? false})
        (log/result "Run saved"))
      (t/is (= "Run saved\n" @(:text stdout)))
      (t/is (= (str/replace expected "✖ failure\n" (str "✖ failure\n  log  " file "\n"))
               @(:text stderr)))
      (t/is (= (str "Phase\n  attempt 1/1 completed\nraw child\nwarning\n✖ failure\n  log  "
                    file "\nRun saved\n")
               (fs/readFileSync file "utf8"))))))

(t/deftest colors-apply-to-console-headings-and-preserve-indented-progress
  (doseq [[mode tty no-color term colored?]
          [[:always false "1" "dumb" true] [:never true "" "xterm" false]
           [:auto false "" "xterm" false] [:auto true "" "xterm" true]
           [:auto true "1" "xterm" false] [:auto true "" "dumb" false]]]
    (let [stdout (capture)
          stderr (capture)
          file   (path/join @temporary-root (str (random-uuid)))
          env    (.-env js/process)
          old-no-color (.-NO_COLOR env)
          old-term (.-TERM env)]
      (try
        (set! (.-NO_COLOR env) no-color)
        (set! (.-TERM env) term)
        (set! (.-isTTY ^js (:stream stderr)) tty)
        (log/configure {:color mode :stdout (:stream stdout) :stderr (:stream stderr)})
        (binding [log/*log-path* file]
          (log/info "Phase\n  progress")
          (log/result "Output /tmp/run"))
        (t/is (= colored? (str/includes? @(:text stderr) "\u001b[")))
        (t/is (= "Phase\n  progress\n"
                 (str/replace @(:text stderr) #"\u001b\[[0-9;]*m" "")))
        (t/is (= "Output /tmp/run\n" @(:text stdout)))
        (t/is (= "Phase\n  progress\nOutput /tmp/run\n" (fs/readFileSync file "utf8")))
        (finally
          (if (nil? old-no-color) (js-delete env "NO_COLOR") (set! (.-NO_COLOR env) old-no-color))
          (if (nil? old-term) (js-delete env "TERM") (set! (.-TERM env) old-term)))))))

(t/deftest ^:async nested-tasks-route-lines-to-their-own-files-on-success-and-failure
  (let [parent (path/join @temporary-root "comparison.log")
        child  (path/join @temporary-root "build.log")
        output (capture)]
    (log/configure {:level :warn :stdout (:stream output) :stderr (:stream output)})
    (binding [log/*log-path* parent]
      (log/debug "comparison before")
      (await ((^:async fn []
                (binding [log/*log-path* child]
                  (await (timers/setImmediate))
                  (log/debug "child success")))))
      (log/debug "comparison between")
      (try
        (await ((^:async fn []
                  (binding [log/*log-path* child]
                    (await (timers/setImmediate))
                    (log/debug "child failure")
                    (throw (js/Error. "failed"))))))
        (catch :default _ (log/debug "comparison after"))))
    (t/is (= "comparison before\ncomparison between\ncomparison after\n"
             (fs/readFileSync parent "utf8")))
    (t/is (= "child success\nchild failure\n" (fs/readFileSync child "utf8")))))

(t/deftest error-names-tail-source-and-bounds-evidence-before-appending
  (let [file   (path/join @temporary-root "comparison.log")
        child  (path/join @temporary-root "build.log")
        output (capture)]
    (fs/writeFileSync child (str "excluded\n" (apply str (repeat 9000 "x")) "\n"
                                 (str/join "\n" (map #(str "line-" %) (range 30)))) "utf8")
    (log/configure {:color :never :stderr (:stream output)})
    (binding [log/*log-path* file]
      (log/error {:message "build failed" :fields [[:phase :renderer] [:build "id"]]
                  :tail-log child}))
    (let [expected (str "✖ build failed\n  phase     :renderer\n  build     id\n"
                        "  log       " file "\n  tail-log  " child "\n  tail\n"
                        (str/join "\n" (map #(str "    line-" %) (range 10 30))) "\n")]
      (t/is (= expected @(:text output)))
      (t/is (= expected (fs/readFileSync file "utf8"))))
    (fs/writeFileSync child (apply str (repeat 10000 "z")) "utf8")
    (reset! (:text output) "")
    (binding [log/*log-path* file]
      (log/error {:message "bounded" :tail-log child}))
    (t/is (= 8192 (count (re-find #"z+$" (str/trim @(:text output))))))
    (fs/writeFileSync file "last line\n" "utf8")
    (reset! (:text output) "")
    (binding [log/*log-path* file]
      (log/error {:message "same source"}))
    (t/is (= (str "✖ same source\n  log  " file "\n  tail\n    last line\n")
             @(:text output)))))

(t/deftest error-reporting-survives-unreadable-tail-and-file-append-failure
  (let [output (capture)]
    (log/configure {:color :never :stderr (:stream output)})
    (binding [log/*log-path* @temporary-root]
      (log/error {:message "original failure" :tail-log (path/join @temporary-root "missing")}))
    (t/is (str/starts-with? @(:text output) "✖ original failure\n"))
    (t/is (str/includes? @(:text output) @temporary-root))))
