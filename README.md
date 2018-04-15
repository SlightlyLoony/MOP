<h1 align="center"><b>MOP</b></h1>
<h2 align="center">Simple Message-Oriented Programming Framework</h2>
<h3 align="center"><i>Tom Dilatush</i></h3>

## What is MOP?
*MOP* is a basic Message-Oriented Programming framework for Java.  But what is Message-Oriented Programming?  There's no real agreement on the concept, but you can read in some detail about the general ideas as I mean them here in [this Wikipedia article on "Message passing"](https://en.wikipedia.org/wiki/Message_passing).  This MOP implementation has just a few main objectives:
* To allow components of a system to be distributed arbitrarily on any process, on any computer.  This objective was inspired by the author's accumulation of small computers (mainly Raspberry Pis) that were each doing some simple job: monitoring temperature, controlling a pump, etc.  These were all isolated systems, each with its own unique network API - but it would be very useful if they could all somehow "talk" to each other.  Ideally code that uses components of the system would neither know nor care what computer or process the component needed was located in.
* To eliminate any need for coupling or linkage between system components.  In many (most) network communication schemes, both ends of the communication have to agree on the precise format of each particular kind of message.  Often this means that both sides must share a class definition that encapsulates the message.  This is the kind of coupling the author sought to avoid.
* To emphasize simplicity over complexity.

## Why does the world need MOP?
Well, probably the world doesn't actually *need* MOP &ndash; it's mainly here for the author's personal use and enjoyment, but with some faintish hope that someone else with the same challenges the author faced will also find it useful.

## What, exactly, does MOP do?
Most fundamentally, MOP provides a communications service for passing messages between <i>actors</i> (to use a bit of message-oriented programming-speak).  An actor is simply a piece of code that knows how to send or receive these messages.

MOP's world consists of a central post office, any number of post offices associated with the central post office, and any number of mailboxes associated with each post office.  Generally any given process will have a single post office, and any actor will have a single mailbox &ndash; but neither of those is actually required.  Each post office handles all the message routing between the mailboxes associated with it.  When it receives a message destined for a mailbox on another post office, it routes the message through the central post office.  The central post office handles all the routing of messages *between* post offices.  This keeps things very simple for a post office: all it needs to know about are (a) all of its own mailboxes, and (b) the location of the central post office.  The central post office is the only part of the system that needs to know where *all* of the post offices are &ndash; which it does automagically when the post offices contact it.

Post offices must authenticate themselves with the central post office before they're allowed to send messages to the central post office, or receive any messages from it.  This authentication is handled with a shared secret, contained as a base64 string in configuration files.  

Selected fields within messages may be encrypted if there is concern about transmitting some information over the network.  The message sender can require any field to be encrypted, and the post office will encrypt it using a key derived from the shared secret and fields in the message envelope.  The central post office will decrypt such messages and re-encrypt them using the destination's shared secret, and the final destination will decrypt them upon receipt.  The encryption is transparent to the message receiver.  There is no provision for encryption of messages that are routed entirely within a single post office.

The messages that MOP handles are instances of the *Message* class.  This class has a few dedicated fields for the "envelope" of the message, containing things like the address of the sender, a unique message ID, the message type, and so on.  The actual contents of the message are determined by the code creating the message, and they can be arbitrary JSON (the *Message* class extends *HJSONObject*, which itself extends *JSONObject*).  MOP itself ignores all message attributes that are not in the envelope.  That means that any actor can construct any message simply by setting message properties by name, or examine any message by retrieving message properties by name.  It also means that message property types are limited to those supported in JSON: string, number, boolean, or null.

MOP transmits messages *asynchronously*, and this is perhaps the biggest difference that someone new to message-oriented programming will see, when compared to the more conventional method-calling.  When your code sends a message through MOP, it's like dropping a letter at the post office.  Your code doesn't know how long it will take for the message to get to its destination, and in the usual case your code won't get a return value as you would with a method call.  Instead, if your code needs a response it will have to wait for a message to come back.  There's one exception to this within MOP: there is a mailbox method that will wait until a response message is received, then return that message as a return value.  The thread sending the message is paused until it is received.  This method is *not* required when replies are expected; it's there as a convenience, to allow code that looks a lot like code with method calls.

There are two general types of MOP messages: "direct" and "publish".  
* Direct messages are sent from one mailbox directly to another mailbox; these messages can be distinguished by the fact that they have a "to" address.  This address is post office name and a mailbox name, separated by a period.  For example, "weather.sensors" is an address to a post office named "weather", and to its mailbox "sensors".  The sender constructing that message doesn't need to know anything else in order to send that message &ndash; the address is all that's required.  
* Publish messages are routed to any mailbox that has subscribed to them.  Each publish message must have a type, but no "to" address.  The type is a major and minor type, separated by a period.  For example, "outdoorWeather.temperature" has a major type of "outdoorWeather", and a minor type of "temperature".  Mailboxes subscribe to messages based on their source address (post office and mailbox of the sender) and their type.  For example, the mailbox "weather.sensors" might publish a message once a minute with the outside temperature, as a type "outdoorWeather.temperature".  Any mailbox that would like to receive these messages can subscribe to them.  A mailbox can also subscribe to just the major type, in which case (in this example) the mailbox might get humidity and barometric pressure messages along with the temperature messages.

MOP includes an abstract *Actor* class that can be used as a base for any actor, though its use is not required.  It creates a mailbox when constructed, and implements a simple message dispatching scheme that makes it easy to write methods to handle particular kinds of messages received.

## Dependencies
MOP has several dependencies.  Some are on other open-source modules also written by the author, and freely available on this GitHub account.  Some are other open-source modules, all documented in the Maven POM.

## Getting started...
Take a look in the examples package to get an idea how to use MPO.  You could also look at some of the test code (classes whose name starts with "Test").

## Why is MOP's code so awful?
The author is a retired software and hardware engineer who did this just for fun, and who (so far, anyway) has no code reviewers to upbraid him.  Please feel free to fill in this gap!  You may contact the author at tom@dilatush.com.

## How is MOP licensed?
MOP is licensed with the quite permissive MIT license:
> Created: April 15, 2018<br>
> Author: Tom Dilatush <tom@dilatush.com><br>
> Github:  https://github.com/SlightlyLoony/MOP<br>
> License: MIT
> 
> Copyright 2018 Tom Dilatush (aka "SlightlyLoony")
> 
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so.
> 
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
> 
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE A AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
