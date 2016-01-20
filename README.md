# vdom-todo

Pre-Alpha Experimentation

Full-stack ClojureScript application to demonstrate the use of a virtual-dom diffing on the server, and a virtual-dom patching on the client.

R.I.P. REST.

## Setup

Install RethinkDB
Install Figwheel
Install Node 4.2.2

Open in two windows/tmux-panes:

`rlwrap lein figwheel dev-server dev-client`

`node target/server_out/server.js`

Open your browser to `localhost:3451`

