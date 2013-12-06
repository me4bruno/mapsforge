/*
 * Copyright 2010, 2011, 2012, 2013 mapsforge.org
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.mapsforge.map.android.graphics;

import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.os.Build;

import org.mapsforge.core.graphics.Bitmap;

import java.io.IOException;
import java.util.Iterator;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AndroidBitmap implements Bitmap {

    private static final Logger LOGGER = Logger.getLogger(AndroidBitmap.class.getName());

	private static AtomicInteger instances;
    private static List<AndroidBitmap> bitmaps;
	static {
		if (AndroidGraphicFactory.debugBitmaps) {
			instances = new AtomicInteger();
			bitmaps = new LinkedList<>();
		}
	}

	private static Set<SoftReference<android.graphics.Bitmap>> reusableBitmaps = new HashSet<>();
	protected static final BitmapFactory.Options BITMAP_FACTORY_OPTIONS = createBitmapFactoryOptions();
    private AtomicInteger refCount = new AtomicInteger();

    private static final BitmapFactory.Options createBitmapFactoryOptions() {
        BitmapFactory.Options bitmapFactoryOptions = new BitmapFactory.Options();
        bitmapFactoryOptions.inPreferredConfig = AndroidGraphicFactory.bitmapConfig;
        return bitmapFactoryOptions;
    }

	protected static android.graphics.Bitmap createAndroidBitmap(int width, int height) {
		return android.graphics.Bitmap.createBitmap(width, height, Config.ARGB_8888);
	}

    protected android.graphics.Bitmap bitmap;

    protected AndroidBitmap() {
        if (AndroidGraphicFactory.debugBitmaps) {
            instances.incrementAndGet();
            synchronized (bitmaps) {
                bitmaps.add(this);
            }
        }
    }

    AndroidBitmap(android.graphics.Bitmap bitmap) {
	    this();
        if (bitmap.isRecycled()) {
            throw new IllegalArgumentException("bitmap is already recycled");
        }
        this.bitmap = bitmap;
    }

    AndroidBitmap(int width, int height) {
	    this();
	    this.bitmap = getBitmapFromReusableSet(width, height);
	    if (this.bitmap == null) {
            this.bitmap = createAndroidBitmap(width, height);
	    }
    }

    @Override
    public void incrementRefCount() {
        this.refCount.incrementAndGet();
    }

    @Override
    public void decrementRefCount() {
        if (this.refCount.decrementAndGet() < 0) {
            destroy();
        }
    }

    @Override
    public void compress(OutputStream outputStream) throws IOException {
        if (!this.bitmap.compress(CompressFormat.PNG, 0, outputStream)) {
            throw new IOException("Failed to write bitmap to output stream");
        }
    }

    protected void destroy() {
        if (AndroidGraphicFactory.debugBitmaps) {
            synchronized (bitmaps) {
                int i = instances.decrementAndGet();
                if (bitmaps.contains(this)) {
                    bitmaps.remove(this);
                } else {
                    LOGGER.log(Level.SEVERE, "BITMAP ALREADY REMOVED " + this.toString());
                }
                LOGGER.log(Level.INFO, "BITMAP COUNT " + Integer.toString(i) + " " + bitmaps.size());
            }
        }
        destroyBitmap();
    }


	protected void destroyBitmap() {
		if (this.bitmap != null) {
			if (org.mapsforge.map.android.util.AndroidUtil.honeyCombPlus) {
				synchronized (reusableBitmaps) {
					reusableBitmaps.add(new SoftReference<>(this.bitmap));
				}
			} else {
				this.bitmap.recycle();
			}
			this.bitmap = null;
		}
	}

    @Override
    public int getHeight() {
	    return this.bitmap.getHeight();
    }

    @Override
    public int getWidth() {
        return this.bitmap.getWidth();
    }

    @Override
    public void scaleTo(int width, int height) {
        if (getWidth() != width || getHeight() != height) {
            android.graphics.Bitmap scaledBitmap = android.graphics.Bitmap.createScaledBitmap(this.bitmap, width,
                    height, false);
            destroy();
            this.bitmap = scaledBitmap;
        }
    }

    @Override
    public String toString() {
        return super.toString() + " refCount " + Integer.toString(refCount.get());
    }

	protected android.graphics.Bitmap getBitmapFromReusableSet(int width, int height) {
		android.graphics.Bitmap bitmap = null;

		if (reusableBitmaps != null && !reusableBitmaps.isEmpty()) {
			synchronized (reusableBitmaps) {
				final Iterator<SoftReference<android.graphics.Bitmap>> iterator = reusableBitmaps.iterator();
				android.graphics.Bitmap candidate;

				while (iterator.hasNext()) {
					candidate = iterator.next().get();
					if (null != candidate && candidate.isMutable()) {
						// Check to see it the item can be used for inBitmap.
						if (canUseBitmap(candidate, width, height)) {
							bitmap = candidate;
							// Remove from reusable set so it can't be used again.
							iterator.remove();
							break;
						}
					} else {
						// Remove from the set if the reference has been cleared.
						iterator.remove();
					}
				}
			}
		}
		return bitmap;
	}

	protected boolean canUseBitmap(android.graphics.Bitmap candidate, int width, int height) {
		if (false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			// THIS SEEMS TO CAUSE LOTS OF SCREEN FLICKERING EVEN THOUGH
			// OLD BITMAPS ARE ERASED

			// From Android 4.4 (KitKat) onward we can re-use if the byte size of
			// the new bitmap is smaller than the reusable bitmap candidate
			// allocation byte count.
			// But has to be reconfigured for new height and width
			int byteCount = width * height * AndroidGraphicFactory.getBytesPerPixel(AndroidGraphicFactory.bitmapConfig);
			boolean reusable = byteCount <= candidate.getAllocationByteCount();
			if (reusable) {
				candidate.reconfigure(width, height, AndroidGraphicFactory.bitmapConfig);
				candidate.eraseColor(0);
			}
			return reusable;
		}

		if (candidate.getWidth() == width && candidate.getHeight() == height) {
			candidate.eraseColor(0);
			return true;
		}
		return false;
	}

}