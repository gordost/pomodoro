# Pomodoro

This implementation of Pomodoro accepts multiple timers + flexible execution schemata.

The "visible" part is implemented as a CLI, while the code from `pomodoro` package may be used in any environment.

I found some inconsistencies in the specification vs. the referred Wiki page, so I've made some assumptions. For example, the spec states that *a timer should give a sort of feedback when done*. However, according to the Wiki page provided, a timer is actually never done (infinite loop). So, I took the timers found in some advanced threadmills (i.e. the threadmills featuring interval training) as a model. These timers switch between the spells of high and low activity, and they get always done.

To enhance reviewing, I have purposely introduced no dependencies in this repo. For example, I'd normally write unit test with the help of JUnit, but I decided against it here.

To keep the scope of this challenge manageable, some simplifications have also been made. For example, I know that an ill-behaved Listener could delay or prevent other Listeners from receiving events, but I refrained from making efforts to mitigate this (this repo, as it is, is probably already too big for a code challenge). Apart from that, some efforts have been made to protect the library part of the repo from malicious consumers.

Another simplification is about the program invoking syntax. Though it is possible to enrich the syntax making the invocation more configurable, I decided against it for simplicity reasons. For example, the folowwing command line argumens could have been added, but they weren't:
- -timeUnits [seconds|minutes|days]
- -maxActiveTimers
- -defaultExecutionScheme <i1> <i2> ...

Finally, not much efforts have been made to produce very slick UI. For example, no command history has been implemented, and the listeners' messages interfere with the input (which can be messy). This is becasue the library part of the repo (i.e. `pomodoro` package) is the most valuable, so this UI has been written merely to demonstrate a possible usage of it.

## Usage

 1. Syntax: `java Pomodoro [-ansi] [-bell]` Add `-ansi` flag if you use Ansi compatible terminal, to add output colouring. As for bell, see 3.
 2. Once inside Pomodoro, type `help` for further help.
 3. Upon a creation of a timer (by using `a` command), `li` command may be used to get visual/audio feedback from the timer. A bell sound should be audiable at the end of every activity, provided you are using the program interactively with listening turned ON. Use `-bell` flag to force bell anyway. 
 4. The commands `li` and `stfu` (without arguments) turn listening ON/OFF on general basis. Once listening is ON, one may want to control listening on timer level by using `li <timer>` or `stfu <timer>`.
 5. Even though the program has been devised as an interactive CLI, it still accepts scripts redirected to STDIN. For example, the following command executes a script and exits when the job is done:  `java Pomodoro -bell < timer.pomodoro`
 
 The contents of `timer.pomodoro`:
 ```
 li
 a myTimer 10 5 10 5 10 15
 sl 56
 bye
 ```

## Tests

 1. Type `java pomodoroTest` to run tests. You may want to add `-Dverbose=true` to see more of the output.


## The Challenge

Challenge: Build a Pomodoro timer, and please include a set of basic tests.

https://en.wikipedia.org/wiki/...

It should:
- Accept a name for a task
- Store the amount of completed pomodoros per task
- List all tasks and the number of pomodoros logged per task
- Give some kind of feedback once the timer is done
- Alternate between pomodoro and break timers

It might:
- Acccept different lengths for breaks
- Allow editing of task names
- Have a GUI
- Be deployed to some kind of cloud solution (clunky URL is fine)

It is really up to you which technologies you choose to solve this challenge. It is purposely designed in a way that accommodates for a range of solutions, from CLI only to fancy cloud-hosted SPAs.

We value testable, straight-to-the-point solutions that shows he potential for being a product engineer.

----------
