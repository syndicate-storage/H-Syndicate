/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package edu.arizona.cs.hsynth.hadoop.connector.output;

import edu.arizona.cs.syndicate.fs.SyndicateFSPath;
import edu.arizona.cs.syndicate.fs.ASyndicateFileSystem;
import edu.arizona.cs.hsynth.hadoop.connector.io.HSynthMapFile;
import edu.arizona.cs.hsynth.hadoop.connector.io.HSynthSequenceFile.CompressionType;
import edu.arizona.cs.syndicate.fs.SyndicateFSConfiguration;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.hsynth.util.SyndicateFileSystemFactory;
import org.apache.hadoop.fs.hsynth.util.HSynthConfigUtils;

/**
 * An {@link org.apache.hadoop.mapreduce.OutputFormat} that writes
 * {@link MapFile}s.
 */
public class HSynthMapFileOutputFormat
        extends HSynthFileOutputFormat<WritableComparable<?>, Writable> {

    public RecordWriter<WritableComparable<?>, Writable> getRecordWriter(
            TaskAttemptContext context) throws IOException {
        Configuration conf = context.getConfiguration();
        CompressionCodec codec = null;
        CompressionType compressionType = CompressionType.NONE;
        if (getCompressOutput(context)) {
            // find the kind of compression to do
            compressionType = HSynthSequenceFileOutputFormat.getOutputCompressionType(context);

            // find the right codec
            Class<?> codecClass = getOutputCompressorClass(context,
                    DefaultCodec.class);
            codec = (CompressionCodec) ReflectionUtils.newInstance(codecClass, conf);
        }

        SyndicateFSPath file = getDefaultWorkFile(context, "");
        ASyndicateFileSystem fs = null;
        try {
            SyndicateFSConfiguration sconf = HSynthConfigUtils.createSyndicateConf(conf, "localhost");
            fs = SyndicateFileSystemFactory.getInstance(sconf);
        } catch (InstantiationException ex) {
            throw new IOException(ex);
        }
        // ignore the progress parameter, since MapFile is local
        final HSynthMapFile.Writer out
                = new HSynthMapFile.Writer(conf, fs, file.toString(),
                        context.getOutputKeyClass().asSubclass(WritableComparable.class),
                        context.getOutputValueClass().asSubclass(Writable.class),
                        compressionType, codec, context);

        return new RecordWriter<WritableComparable<?>, Writable>() {
            public void write(WritableComparable<?> key, Writable value)
                    throws IOException {
                out.append(key, value);
            }

            public void close(TaskAttemptContext context) throws IOException {
                out.close();
            }
        };
    }

    /**
     * Open the output generated by this format.
     */
    public static HSynthMapFile.Reader[] getReaders(SyndicateFSPath dir,
            Configuration conf) throws IOException {
        ASyndicateFileSystem fs = null;
        try {
            SyndicateFSConfiguration sconf = org.apache.hadoop.fs.hsynth.util.HSynthConfigUtils.createSyndicateConf(conf, "localhost");
            fs = SyndicateFileSystemFactory.getInstance(sconf);
        } catch (InstantiationException ex) {
            throw new IOException(ex);
        }
        SyndicateFSPath[] names = fs.listAllFiles(dir);

        // sort names, so that hash partitioning works
        Arrays.sort(names);

        HSynthMapFile.Reader[] parts = new HSynthMapFile.Reader[names.length];
        for (int i = 0; i < names.length; i++) {
            parts[i] = new HSynthMapFile.Reader(fs, names[i].toString(), conf);
        }
        return parts;
    }

    /**
     * Get an entry from output generated by this class.
     */
    public static <K extends WritableComparable<?>, V extends Writable> Writable getEntry(HSynthMapFile.Reader[] readers,
            Partitioner<K, V> partitioner, K key, V value) throws IOException {
        int part = partitioner.getPartition(key, value, readers.length);
        return readers[part].get(key, value);
    }
}
