package com.intellij.lifecycle;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class SlowlyClosingAlarm implements AtomicSectionsAware, Disposable {
  private final static Logger LOG = Logger.getInstance("#com.intellij.lifecycle.SlowlyClosingAlarm");

  protected final ExecutorService myExecutorService;
  // single threaded executor, so we have "shared" state here 
  private boolean myInUninterruptibleState;
  protected boolean myDisposeStarted;
  private boolean myFinished;
  // for own threads only
  protected final List<Future<?>> myFutureList;
  protected final Object myLock;

  private static final ThreadFactory THREAD_FACTORY_OWN = threadFactory("SlowlyClosingAlarm pool");
  private final String myName;
  private final boolean myExecutorIsShared;

  private static ThreadFactory threadFactory(@NonNls final String threadsName) {
    return new ThreadFactory() {
      public Thread newThread(final Runnable r) {
        return new Thread(r, threadsName);
      }
    };
  }

  public void dispose() {
    synchronized (myLock) {
      safelyShutdownExecutor();
      for (Future<?> future : myFutureList) {
        future.cancel(true);
      }
      myFutureList.clear();
    }
  }

  protected SlowlyClosingAlarm(@NotNull final Project project, @NotNull final String name) {
    this(project, name, Executors.newSingleThreadExecutor(THREAD_FACTORY_OWN), false);
  }

  protected SlowlyClosingAlarm(@NotNull final Project project, @NotNull final String name, final ExecutorService executor,
                               final boolean executorIsShared) {
    myName = name;
    myExecutorIsShared = executorIsShared;
    myExecutorService = executor;
    myLock = new Object();
    myFutureList = new ArrayList<Future<?>>();
    Disposer.register(project, this);
    PeriodicalTasksCloser.getInstance(project).register(name, new Runnable() {
      public void run() {
        waitAndInterrupt(ProgressManager.getInstance().getProgressIndicator());
      }
    });
  }

  protected void debug(final String s) {
    LOG.debug(myName + " " + s);
  }

  public void addRequest(final Runnable runnable) {
    synchronized (myLock) {
      if (myDisposeStarted) return;
      final MyWrapper wrapper = new MyWrapper(runnable);
      final Future<?> future = myExecutorService.submit(wrapper);
      wrapper.setFuture(future);
      myFutureList.add(future);
      debug("request added");
    }
  }

  private void stopSelf() {
    if (myExecutorIsShared) {
      throw new ProcessCanceledException();
    } else {
      Thread.currentThread().interrupt();
    }
  }

  public void enter() {
    synchronized (myLock) {
      debug("entering section");
      if (myDisposeStarted) {
        debug("self-interrupting (1)");
        stopSelf();
      }
      myInUninterruptibleState = true;
    }
  }

  public void exit() {
    debug("exiting section");
    synchronized (myLock) {
      myInUninterruptibleState = false;
      if (myDisposeStarted) {
        debug("self-interrupting (2)");
        stopSelf();
      }
    }
  }

  public boolean shouldExitAsap() {
    synchronized (myLock) {
      return myDisposeStarted;
    }
  }
  
  public void checkShouldExit() throws ProcessCanceledException {
    synchronized (myLock) {
      if (myDisposeStarted) {
        stopSelf();
      }
    }
  }

  private void safelyShutdownExecutor() {
    synchronized (myLock) {
      if (! myExecutorIsShared) {
        try {
          myExecutorService.shutdown();
        } catch (SecurityException e) {
          //
        }
      }
    }
  }

  public void waitAndInterrupt(@Nullable final ProgressIndicator indicator) {
    final List<Future<?>> copy;
    synchronized (myLock) {
      debug("starting shutdown: " + myFutureList.size());
      myDisposeStarted = true;
      safelyShutdownExecutor();

      copy = new ArrayList<Future<?>>(myFutureList.size());
      for (Future<?> future : myFutureList) {
        if (future.isDone()) continue;
        copy.add(future);
      }
    }

    debug("waiting for gets");
    boolean wasCanceled = false;
    for (Future<?> future : copy) {
      if (wasCanceled) break;
      while (true) {
        try {
          if (indicator == null) {
            future.get();
          } else {
            future.get(500, TimeUnit.MILLISECONDS);
          }
        } catch (CancellationException e) {
          break;
        } catch (InterruptedException e) {
          break;
        }
        catch (ExecutionException e) {
          break;
        }
        catch (TimeoutException e) {
          if (indicator != null) {
            wasCanceled |= indicator.isCanceled();
            if (wasCanceled) {
              break;
            }
            debug("was canceled");
          }
          continue;
        }
        break;
      }
    }

    debug("finishing " + myInUninterruptibleState);
    synchronized (myLock) {
      for (Future<?> future : myFutureList) {
        future.cancel(true);
      }
      myFutureList.clear();
      myFinished = true;
    }
    debug("done");
  }

  protected class MyWrapper implements Runnable {
    private final Runnable myDelegate;
    private Future myFuture;

    protected MyWrapper(final Runnable delegate) {
      myDelegate = delegate;
    }

    public void setFuture(final Future future) {
      myFuture = future;
    }

    public void run() {
      try {
        debug("wrapper starts runnable");
        myDelegate.run();
        debug("wrapper: runnable succesfully finished");
      } finally {
        // anyway, the owner Future is no more interesting for us: its task is finished and does not require "anti-closing" defence
        if (myFuture != null) {
          debug("removing future");
          synchronized (myLock) {
            myFutureList.remove(myFuture);
          }
        }
      }
    }
  }
}
