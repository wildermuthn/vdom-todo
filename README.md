# vdom-todo

Pre-Alpha Experimentation

Full-stack ClojureScript application to demonstrate the use of a virtual-dom diffing on the server, and a virtual-dom patching on the client.

R.I.P. REST.

## Setup

Install RethinkDB
Install Figwheel
Install Node 4.2.2

## Install

`npm install`

`lein deps`

## Start rethinkdb

`rethinkdb`

### Start nodejs figwheel

Use tmux or tabs. Wait for each command to finish.

`git checkout server`

`rlwrap lein figwheel`

`node target/server_out/server.js`

Change to `server.core` namespace, and run the comments from the repl to initialize your rethinkdb database. Check `localhost:8080` to ensure your db and table is set correctly.

### Start browser figwheel

Use a different tmux pane or terminal tab.

`git checkout client`

`rlwrap lein figwheel`

Open your browser to `localhost:3450`

