;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.log
  "Console output and scoped file logs for the benchmark CLI.

  Public API:

      (log/configure {:level :info
                      :color :auto
                      :stdout stream
                      :stderr stream})
      (log/result text)
      (log/info text)
      (log/debug text)
      (log/warn text)
      (log/error {:message ...
                  :fields [...]
                  :tail-log ...})

  Every output function uses the same private one:

     ...
      └── log/write  <-- this guy
            ├── append text to the bound log file
            └── filter and render console output
                  ├── result → stdout
                  └── diagnostics → stderr

  The CLI selects the logging threshold for the console:
    - Default: :info
    - --quiet: :warn
    - --verbose: :debug

  But console filtering and file logging are independent:

    | Function | Console destination | Console visibility  | File log |
    |----------|---------------------|---------------------|----------|
    | result   | stdout              | Always              | Always   |
    | info     | stderr              | Default and verbose | Always   |
    | debug    | stderr              | Verbose             | Always   |
    | warn     | stderr              | Always              | Always   |
    | error    | stderr              | Always              | Always   |

  So: quiet mode still records detailed diagnostics in files.
  Also, the console renderer adds headings, prefixes, and colors."
  (:require
    ["fs" :as fs]
    [clojure.string :as str]))

;; Each high-level task binds its own path and awaits its work inside that scope:
;;   (binding [log/*log-path* (:log-path home)]
;;     (await (execute-task ...)))
;; Returning or throwing from a child restores the parent's destination
(def ^:dynamic *log-path* nil)

(defonce ^:private config (atom nil))

(defn configure
  "Configures console level, color mode and output streams."
  [options]
  (reset! config (merge {:level :info :color :auto
                         :stdout (.-stdout js/process)
                         :stderr (.-stderr js/process)}
                        options)))

(defn- color?
  [stream mode]
  (case mode
    :always true
    :never false
    (and (.-isTTY ^js stream)
         (empty? (.-NO_COLOR (.-env js/process)))
         (not= "dumb" (.-TERM (.-env js/process))))))

(defn- paint
  [enabled code text]
  (if enabled (str "\u001b[" code "m" text "\u001b[0m") text))

(defn- render
  [level text color]
  (str/join "\n"
            (map (fn [line]
                   (case level
                     :info (if (re-find #"^\S" line) (paint color "1;36" line) line)
                     :debug (paint color "2" (str "· " line))
                     :warn (paint color "33" (str "! " line))
                     :error (paint color "31" line)
                     line))
                 (str/split-lines text))))

(defn- write
  [level text]
  (when-not @config (configure {}))
  (let [{:keys [stdout stderr color] console-level :level} @config
        text    (str/replace (str text) #"\n$" "")
        failure (when *log-path*
                  (try
                    (fs/appendFileSync *log-path* (str text "\n") "utf8")
                    nil
                    (catch :default cause
                      (ex-info "cannot append benchmark log"
                               {:type ::storage-error :log-path *log-path*} cause))))
        enabled (case level
                  :debug (= :debug console-level)
                  :info (contains? #{:info :debug} console-level)
                  true)
        stream  (if (= :result level) stdout stderr)]
    (when enabled
      (.write ^js stream (str (if (= :result level)
                                text
                                (render level text (color? stream color))) "\n")))
    (when failure
      (if (= :error level)
        (.write ^js stderr (str "Could not append log: " *log-path* "\n"))
        (throw failure)))))

(defn result [text] (write :result text))
(defn info [text] (write :info text))
(defn debug [text] (write :debug text))
(defn warn [text] (write :warn text))

(defn- get-tail
  [path]
  (when path
    (try
      (let [fd (fs/openSync path "r")]
        (try
          (let [size   (.-size (fs/fstatSync fd))
                length (min size 8192)
                buffer (js/Buffer.alloc length)
                read   (fs/readSync fd buffer 0 length (- size length))]
            (->> (str/split-lines (.toString buffer "utf8" 0 read))
                 (remove str/blank?)
                 (take-last 20)))
          (finally (fs/closeSync fd))))
      (catch :default _ nil))))

(defn error
  "Prints a terminal failure with ordered fields and an optional bounded log tail."
  [{:keys [message fields tail-log tail?] :or {tail? true}}]
  (let [tail-path (or tail-log *log-path*)
        fields    (cond-> (vec fields)
                    (and *log-path* (not (some #(= :log (first %)) fields)))
                    (conj [:log *log-path*])
                    (and tail-log (not= tail-log *log-path*))
                    (conj [:tail-log tail-log]))
        width     (reduce max 0 (map #(count (name (first %))) fields))
        tail      (when tail? (get-tail tail-path))]
    (write :error
           (str "✖ " message
                (apply str
                       (map (fn [[key value]]
                              (let [label (name key)]
                                (str "\n  " label
                                     (apply str (repeat (+ 2 (- width (count label))) " "))
                                     value)))
                            fields))
                (when (seq tail)
                  (str "\n  tail\n" (str/join "\n" (map #(str "    " %) tail))))))))
